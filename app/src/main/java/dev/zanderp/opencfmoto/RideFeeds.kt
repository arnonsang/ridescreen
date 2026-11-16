// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.ComponentName
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The slow sources behind the ride screens: weather, the street you are on, fuel range from the
 * refuel log, and what is playing. Each is throttled and fails quietly — a ride screen shows a dash
 * for anything that isn't known.
 */
object RideFeeds {
    // Lazy: the pure helpers below are unit-tested on a plain JVM, where there is no main looper.
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "ride-feeds").apply { isDaemon = true } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private const val WEATHER_EVERY_MS = 10 * 60_000L
    private const val STREET_EVERY_MS = 20_000L
    private const val STREET_MIN_MOVE_M = 120f
    private const val RANGE_EVERY_MS = 5 * 60_000L

    @Volatile private var weatherAt = 0L
    @Volatile private var streetAt = 0L
    @Volatile private var rangeAt = 0L
    private var streetLoc: Location? = null

    /** Called on every GPS fix while a ride screen can be showing. Cheap unless something is due. */
    fun onFix(ctx: Context, loc: Location) {
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (weatherAt == 0L || now - weatherAt > WEATHER_EVERY_MS) {
            weatherAt = now
            io.execute { try { weather(loc) } catch (e: Exception) { LogBus.log("[RIDE] weather skipped: ${e.javaClass.simpleName}") } }
        }
        val moved = streetLoc?.distanceTo(loc) ?: Float.MAX_VALUE
        if ((streetAt == 0L || now - streetAt > STREET_EVERY_MS) && moved > STREET_MIN_MOVE_M) {
            streetAt = now; streetLoc = Location(loc)
            io.execute { try { street(app, loc) } catch (_: Exception) {} }
        }
        if (rangeAt == 0L || now - rangeAt > RANGE_EVERY_MS) {
            rangeAt = now
            io.execute { try { RideData.rangeKm = fuelRange(app); RideData.touch() } catch (_: Exception) {} }
        }
        NowPlaying.ensure(app)
    }

    /** Temperature now and when rain starts, from Open-Meteo (the service the rain alert already uses). */
    private fun weather(loc: Location) {
        val lat = String.format(Locale.ROOT, "%.2f", loc.latitude)
        val lon = String.format(Locale.ROOT, "%.2f", loc.longitude)
        val root = JSONObject(
            AppHttp.getText(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m&minutely_15=precipitation&forecast_minutely_15=12&timezone=auto",
            ),
        )
        val temp = root.optJSONObject("current")?.optDouble("temperature_2m", Double.NaN)
        RideData.tempC = temp?.takeIf { !it.isNaN() }?.toFloat()
        RideData.rainNote = rainNote(root.optJSONObject("minutely_15")?.optJSONArray("precipitation")?.let { a ->
            DoubleArray(a.length()) { a.optDouble(it, 0.0) }
        })
        RideData.touch()
    }

    /** [mmPer15] = forecast precipitation per 15-minute slot, starting now. */
    internal fun rainNote(mmPer15: DoubleArray?): String? {
        mmPer15 ?: return null
        if (mmPer15.isEmpty()) return null
        val first = mmPer15.indexOfFirst { it >= 0.1 }
        return when {
            first < 0 -> "No rain for ${mmPer15.size / 4} h"
            first == 0 -> "Raining now"
            first * 15 < 60 -> "Rain in about ${first * 15} min"
            else -> "Rain in about ${first / 4} h"
        }
    }

    private fun street(ctx: Context, loc: Location) {
        if (!Geocoder.isPresent()) return
        @Suppress("DEPRECATION")
        val a = Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() ?: return
        val name = a.thoroughfare ?: a.subLocality ?: a.locality
        if (!name.isNullOrBlank()) { RideData.street = name; RideData.touch() }
    }

    /**
     * Rough range left: the last fill-up divided by your average consumption, minus what you have
     * ridden since. Needs two logged fill-ups; assumes each one filled the tank.
     */
    internal fun fuelRange(ctx: Context): Int? {
        val book = Maintenance.load(ctx)
        val per100 = Maintenance.consumption(book)?.first?.takeIf { it > 0.5 } ?: return null
        val last = book.log.filter { it.kind == "fuel" && it.liters != null }.maxByOrNull { it.at } ?: return null
        val since = (book.appKm - last.km).coerceAtLeast(0.0)
        return rangeKm(last.liters ?: return null, per100, since)
    }

    internal fun rangeKm(lastLiters: Double, per100: Double, kmSince: Double): Int? {
        if (per100 <= 0.0) return null
        return (lastLiters / per100 * 100.0 - kmSince).toInt().coerceAtLeast(0)
    }

    /** What the phone is playing, read from its media sessions (needs Notification access). */
    object NowPlaying {
        private var manager: MediaSessionManager? = null
        private var controller: MediaController? = null
        private var listening = false
        private var checkedAt = 0L

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == ctx.packageName }
        }

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
            override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
            override fun onSessionDestroyed() { pick(manager?.let { safeSessions(it) } ?: emptyList()) }
        }
        private var component: ComponentName? = null
        private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list ?: emptyList()) }

        private fun safeSessions(m: MediaSessionManager): List<MediaController> = try {
            m.getActiveSessions(component)
        } catch (_: SecurityException) { emptyList() }

        /** Start following the phone's player once access is granted. Safe to call often. */
        fun ensure(ctx: Context) {
            val now = SystemClock.elapsedRealtime()
            if (listening || now - checkedAt < 15_000L) return
            checkedAt = now
            if (!hasAccess(ctx)) return
            main.post {
                try {
                    val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                    val c = ComponentName(ctx, NowPlayingListener::class.java)
                    component = c; manager = m
                    m.addOnActiveSessionsChangedListener(sessionsChanged, c, main)
                    listening = true
                    pick(safeSessions(m))
                    LogBus.log("[RIDE] now playing: following the phone's media player")
                } catch (e: Exception) {
                    LogBus.log("[RIDE] now playing unavailable: ${e.javaClass.simpleName}")
                }
            }
        }

        private fun pick(list: List<MediaController>) {
            // Our own "Android Auto control" session exists only to catch handlebar buttons.
            val next = list.firstOrNull { it.packageName != BuildConfig.APPLICATION_ID && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull { it.packageName != BuildConfig.APPLICATION_ID }
            if (next?.sessionToken == controller?.sessionToken) { publish(); return }
            try { controller?.unregisterCallback(callback) } catch (_: Exception) {}
            controller = next
            try { next?.registerCallback(callback, main) } catch (_: Exception) {}
            publish()
        }

        private fun publish() {
            val c = controller
            val md = c?.metadata
            val st = c?.playbackState
            RideData.nowTitle = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            RideData.nowArtist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }
            RideData.nowDurMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
            RideData.nowPosMs = st?.position?.takeIf { it >= 0 }
            RideData.nowPlaying = st?.state == PlaybackState.STATE_PLAYING
            RideData.nowReadAt = SystemClock.elapsedRealtime()
            RideData.touch()
        }
    }
}

/**
 * Exists so Android lets the app read the phone's media sessions for "now playing". It does not
 * read, store or act on any notification.
 */
class NowPlayingListener : NotificationListenerService()
