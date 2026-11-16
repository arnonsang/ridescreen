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
        CLASSIC("classic", "Classic"),
        COMMUTE("commute", "Commute"),
        TRIP("trip", "Trip computer"),
        NIGHT("night", "Night"),
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

    /** What the dash shows: Android Auto, one of these ride screens, or the phone's screen mirrored. */
    enum class Mode(val key: String) { AA("aa"), RIDE("ride"), MIRROR("mirror") }

    /** Pieces of information the rider can hide from every layout. */
    enum class Field(val key: String, val label: String) {
        WEATHER("weather", "Weather"),
        BATTERY("battery", "Bike battery"),
        RANGE("range", "Fuel range"),
        MUSIC("music", "Now playing"),
        LIMIT("limit", "Speed limit"),
    }

    /** Accent colours offered on top of each theme's own (0 = keep the theme's). */
    val ACCENTS = intArrayOf(
        0, 0xFFD1A955.toInt(), 0xFFFF5A1F.toInt(), 0xFFFF3B30.toInt(), 0xFFFF4FD8.toInt(),
        0xFF9B6BFF.toInt(), 0xFF3D9BFF.toInt(), 0xFF22E3F0.toInt(), 0xFF5BD47A.toInt(), 0xFFF4F5F7.toInt(),
    )

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

    fun mode(ctx: Context): Mode {
        val k = prefs(ctx).getString("mode", null)
        return Mode.entries.firstOrNull { it.key == k } ?: if (enabled(ctx)) Mode.RIDE else Mode.AA
    }
    fun setMode(ctx: Context, m: Mode) {
        prefs(ctx).edit().putString("mode", m.key).putBoolean("enabled", m == Mode.RIDE).apply(); revision++
    }

    /** Speed alert: km/h over the limit before the speed turns red (0 = off). */
    fun alertMargin(ctx: Context): Int = prefs(ctx).getInt("alert_margin", 10)
    fun setAlertMargin(ctx: Context, kmh: Int) { prefs(ctx).edit().putInt("alert_margin", kmh).apply(); revision++ }
    fun alertBeep(ctx: Context): Boolean = prefs(ctx).getBoolean("alert_beep", false)
    fun setAlertBeep(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("alert_beep", on).apply(); revision++ }

    /** Daylight theme while the sun is up, the chosen theme after sunset. */
    fun autoTheme(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_theme", false)
    fun setAutoTheme(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("auto_theme", on).apply(); revision++ }
    /** Dim the whole screen after sunset. */
    fun nightDim(ctx: Context): Boolean = prefs(ctx).getBoolean("night_dim", false)
    fun setNightDim(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("night_dim", on).apply(); revision++ }

    /** Accent override, 0 = the theme's own. */
    fun customAccent(ctx: Context): Int = prefs(ctx).getInt("accent", 0)
    fun setCustomAccent(ctx: Context, color: Int) { prefs(ctx).edit().putInt("accent", color).apply(); revision++ }

    fun hidden(ctx: Context): Set<String> = prefs(ctx).getStringSet("hidden", emptySet()) ?: emptySet()
    fun setHidden(ctx: Context, f: Field, hide: Boolean) {
        val next = HashSet(hidden(ctx)).apply { if (hide) add(f.key) else remove(f.key) }
        prefs(ctx).edit().putStringSet("hidden", next).apply(); revision++
    }

    /** Is it dark out? From today's sunrise and sunset where the position is known, else the clock. */
    fun isNight(lat: Double?, lon: Double?, now: Calendar = Calendar.getInstance()): Boolean {
        val min = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val sun = if (lat != null && lon != null) RideData.sunTimes(lat, lon, now) else null
        return if (sun != null && sun.second > sun.first) min < sun.first || min > sun.second else min < 6 * 60 || min >= 18 * 60
    }

    /** Over the limit by more than the margin? Pure, so it can be unit-tested. */
    fun overLimit(speedKmh: Int, limitKmh: Int?, marginKmh: Int): Boolean =
        limitKmh != null && limitKmh > 0 && marginKmh > 0 && speedKmh > limitKmh + marginKmh

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
    /** Metres climbed and descended this trip, from GPS altitude (rough). */
    @Volatile var ascentM = 0.0
    @Volatile var descentM = 0.0
    /** Forecast rain per 15-minute slot starting now, mm. */
    @Volatile var rainSlots: DoubleArray? = null
    private var altRef: Double? = null

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
        ascentM = 0.0; descentM = 0.0; altRef = null
        cueText = null; cueDist = null; arrival = null; remaining = null
        revision++
    }

    @Synchronized
    fun onFix(loc: Location, speed: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        // First fix after a long gap is a new ride.
        if (lastFixAt > 0L && now - lastFixAt > 30 * 60_000L) {
            tripM = 0.0; movingMs = 0L; maxKmh = 0f; lastFix = null; ascentM = 0.0; descentM = 0.0; altRef = null
        }
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
        if (loc.hasAltitude()) {
            altitudeM = loc.altitude.toInt()
            // Climb and descent: only count steps bigger than GPS altitude noise.
            if (!loc.hasAccuracy() || loc.accuracy < 25f) {
                val (ref, up, down) = climbStep(altRef, loc.altitude)
                altRef = ref; ascentM += up; descentM += down
            }
        }
        if (loc.hasAccuracy()) accuracyM = loc.accuracy.toInt()
        lat = loc.latitude; lon = loc.longitude
        revision++
    }

    /**
     * One altitude reading against the last counted level [ref]: returns the new level and the
     * metres to add to climb and descent. Changes under [CLIMB_STEP_M] are treated as noise.
     */
    internal fun climbStep(ref: Double?, alt: Double): Triple<Double, Double, Double> {
        if (ref == null) return Triple(alt, 0.0, 0.0)
        val d = alt - ref
        return when {
            d >= CLIMB_STEP_M -> Triple(alt, d, 0.0)
            d <= -CLIMB_STEP_M -> Triple(alt, 0.0, -d)
            else -> Triple(ref, 0.0, 0.0)
        }
    }
    private const val CLIMB_STEP_M = 5.0

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
