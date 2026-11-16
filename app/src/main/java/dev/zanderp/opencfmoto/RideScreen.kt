// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.location.Location
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * "Ride screen": the app's own instrument layouts for the dash, shown instead of Android Auto.
 * They ride on the built-in map session (same video path, same handlebar buttons): [RideScreenView]
 * is drawn over the map inside the dash Presentation, and one preset leaves a window for the map.
 */
object RideScreen {
    /** Layouts. [mapWindow] presets leave part of the screen to the live map underneath. */
    enum class Preset(val key: String, val label: String, val mapWindow: Boolean = false) {
        CRUISE("cruise", "Cruise"),
        STREET("street", "Street"),
        HUB("hub", "Ride hub", mapWindow = true),
        TOURER("tourer", "Tourer", mapWindow = true),
        SPORT("sport", "Sport"),
        MUSIC("music", "Music"),
        RACE("race", "Race bar"),
        TWIN("twin", "Twin dials"),
    }

    /** Colour sets. [light] themes use dark ink and softer shadows. */
    enum class Theme(
        val key: String, val label: String,
        val bg: Int, val surf: Int, val ink: Int, val mute: Int, val line: Int,
        val accent: Int, val accent2: Int, val onAccent: Int, val light: Boolean = false,
    ) {
        GOLD("gold", "Gold 368K", 0xFF0B0C0E.toInt(), 0xFF15171B.toInt(), 0xFFF4F5F7.toInt(), 0xFFA3A8B3.toInt(), 0xFF2C3038.toInt(), 0xFFD1A955.toInt(), 0xFF4FD1C5.toInt(), 0xFF0B0C0E.toInt()),
        RACING("racing", "Racing red", 0xFF0C0B0B.toInt(), 0xFF1A1515.toInt(), 0xFFF7F4F4.toInt(), 0xFFB3A6A6.toInt(), 0xFF3A2C2C.toInt(), 0xFFFF3B30.toInt(), 0xFFFFB020.toInt(), 0xFF0C0B0B.toInt()),
        NEON("neon", "Neon night", 0xFF070A1A.toInt(), 0xFF111634.toInt(), 0xFFEEF2FF.toInt(), 0xFF9AA6D6.toInt(), 0xFF263060.toInt(), 0xFF22E3F0.toInt(), 0xFFFF4FD8.toInt(), 0xFF070A1A.toInt()),
        SUNSET("sunset", "Sunset", 0xFF140A14.toInt(), 0xFF231326.toInt(), 0xFFFFF3EC.toInt(), 0xFFC7A9B8.toInt(), 0xFF3D2440.toInt(), 0xFFFF7A1A.toInt(), 0xFFFF4F8B.toInt(), 0xFF140A14.toInt()),
        DAYLIGHT("daylight", "Daylight", 0xFFF4F5F7.toInt(), 0xFFFFFFFF.toInt(), 0xFF0B0C0E.toInt(), 0xFF4A4F59.toInt(), 0xFFD5D8DE.toInt(), 0xFFC93A04.toInt(), 0xFF0B63CE.toInt(), 0xFFFFFFFF.toInt(), light = true),
    }

    private const val PREFS = "ride_screen"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Bumped on every change so live views can redraw without a listener registry. */
    @Volatile var revision = 0
        private set

    /** Show the instrument layout over the dash map (false = plain map, as before). */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("enabled", on).apply(); revision++ }

    fun preset(ctx: Context): Preset {
        val k = prefs(ctx).getString("preset", null)
        return Preset.entries.firstOrNull { it.key == k } ?: Preset.CRUISE
    }
    fun setPreset(ctx: Context, p: Preset) { prefs(ctx).edit().putString("preset", p.key).apply(); revision++ }

    fun theme(ctx: Context): Theme {
        val k = prefs(ctx).getString("theme", null)
        return Theme.entries.firstOrNull { it.key == k } ?: Theme.GOLD
    }
    fun setTheme(ctx: Context, t: Theme) { prefs(ctx).edit().putString("theme", t.key).apply(); revision++ }

    /** Next/previous preset (handlebar left/right). */
    fun cyclePreset(ctx: Context, delta: Int): Preset {
        val all = Preset.entries
        val next = all[(preset(ctx).ordinal + delta).mod(all.size)]
        setPreset(ctx, next)
        return next
    }
}

/**
 * What the ride screens show, gathered in one place. Filled by the dash map on every GPS fix
 * ([onFix]) and by a few slow sources; anything unknown stays null and is drawn as a dash.
 */
object RideData {
    @Volatile var speedKmh = 0f
    @Volatile var headingDeg: Float? = null
    @Volatile var altitudeM: Int? = null
    @Volatile var accuracyM: Int? = null
    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null
    @Volatile var tripM = 0.0
    @Volatile var movingMs = 0L
    @Volatile var maxKmh = 0f
    @Volatile var limitKmh: Int? = null
    /** Next manoeuvre from the built-in map, e.g. "Turn right" and "300 m". */
    @Volatile var cueText: String? = null
    @Volatile var cueDist: String? = null
    @Volatile var arrival: String? = null
    @Volatile var remaining: String? = null
    @Volatile var street: String? = null
    @Volatile var tempC: Float? = null
    @Volatile var rainNote: String? = null
    @Volatile var batteryV: Float? = null
    @Volatile var rangeKm: Int? = null
    @Volatile var nowTitle: String? = null
    @Volatile var nowArtist: String? = null
    /** Playback position and length in ms, with the moment they were read; null when unknown. */
    @Volatile var nowPosMs: Long? = null
    @Volatile var nowDurMs: Long? = null
    @Volatile var nowPlaying = false
    @Volatile var nowReadAt = 0L
    @Volatile var phonePct: Int? = null

    /** Bumped whenever something changes, so the view only redraws when needed. */
    @Volatile var revision = 0
        private set

    private var lastFix: Location? = null
    private var lastFixAt = 0L

    val avgKmh: Float get() = if (movingMs > 5_000L) (tripM / (movingMs / 1000.0) * 3.6).toFloat() else 0f

    /** A new ride starts: trip counters back to zero. */
    @Synchronized
    fun resetTrip() {
        tripM = 0.0; movingMs = 0L; maxKmh = 0f; lastFix = null; lastFixAt = 0L
        cueText = null; cueDist = null; arrival = null; remaining = null
        revision++
    }

    @Synchronized
    fun onFix(loc: Location, speed: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        // First fix after a long gap is a new ride.
        if (lastFixAt > 0L && now - lastFixAt > 30 * 60_000L) { tripM = 0.0; movingMs = 0L; maxKmh = 0f; lastFix = null }
        val prev = lastFix
        if (prev != null && lastFixAt > 0L) {
            val dt = now - lastFixAt
            val d = prev.distanceTo(loc)
            // Same sanity rule as the trip recorder: ignore jumps no bike can make.
            if (dt in 1..10_000 && d / (dt / 1000f) < 70f && speed >= 3f) {
                tripM += d
                movingMs += dt
            }
        }
        lastFix = loc; lastFixAt = now
        speedKmh = speed.coerceAtLeast(0f)
        if (speedKmh > maxKmh && speedKmh < 250f) maxKmh = speedKmh
        if (loc.hasBearing() && speedKmh > 5f) headingDeg = loc.bearing
        if (loc.hasAltitude()) altitudeM = loc.altitude.toInt()
        if (loc.hasAccuracy()) accuracyM = loc.accuracy.toInt()
        lat = loc.latitude; lon = loc.longitude
        revision++
    }

    fun setCue(text: String?, dist: String?, arrivalAt: String?, left: String?) {
        cueText = text; cueDist = dist; arrival = arrivalAt; remaining = left
        revision++
    }

    fun touch() { revision++ }

    fun headingLabel(deg: Float?): String {
        deg ?: return "—"
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((deg % 360f) + 360f) % 360f / 45f + 0.5f).toInt() % 8]
    }

    /**
     * Sunrise and sunset today as minutes after local midnight, or null where the sun doesn't rise
     * or set. Standard almanac approximation, good to a couple of minutes.
     */
    fun sunTimes(lat: Double, lon: Double, now: Calendar = Calendar.getInstance()): Pair<Int, Int>? {
        val n = now.get(Calendar.DAY_OF_YEAR)
        val tzH = TimeZone.getDefault().getOffset(now.timeInMillis) / 3_600_000.0
        fun calc(rise: Boolean): Double? {
            val lngHour = lon / 15.0
            val t = n + ((if (rise) 6.0 else 18.0) - lngHour) / 24.0
            val m = 0.9856 * t - 3.289
            var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
            l = (l % 360 + 360) % 360
            var ra = Math.toDegrees(kotlin.math.atan(0.91764 * tan(Math.toRadians(l))))
            ra = (ra % 360 + 360) % 360
            ra = (ra + (floor(l / 90) * 90 - floor(ra / 90) * 90)) / 15.0
            val sinDec = 0.39782 * sin(Math.toRadians(l))
            val cosDec = cos(kotlin.math.asin(sinDec))
            val cosH = (cos(Math.toRadians(90.833)) - sinDec * sin(Math.toRadians(lat))) /
                (cosDec * cos(Math.toRadians(lat)))
            if (cosH > 1 || cosH < -1) return null
            val h = (if (rise) 360 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))) / 15.0
            val ut = h + ra - 0.06571 * t - 6.622 - lngHour
            return ((ut + tzH) % 24 + 24) % 24
        }
        val r = calc(true) ?: return null
        val s = calc(false) ?: return null
        return (r * 60).toInt() to (s * 60).toInt()
    }
}
