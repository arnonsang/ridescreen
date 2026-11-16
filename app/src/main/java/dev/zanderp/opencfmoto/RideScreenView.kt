// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a [RideScreen.Preset] in a [RideScreen.Theme]. Everything is laid out in a 1024 × 425
 * design space (the Zontes 368K panel) and scaled to the view, so other panels letterbox cleanly.
 *
 * On the dash it sits over the map and reads live values from [RideData]. With [demo] set it shows
 * sample values instead: that is the phone's preview of what the dash will look like.
 */
class RideScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Sample values instead of live ones (phone preview). */
    var demoSpeed = 47
    var demo = false
        set(v) { field = v; invalidate() }

    /** Fixed preset/theme for a preview; null follows the saved choice. */
    var presetOverride: RideScreen.Preset? = null
        set(v) { field = v; invalidate() }
    var themeOverride: RideScreen.Theme? = null
        set(v) { field = v; invalidate() }

    private val num: Typeface = try {
        ResourcesCompat.getFont(context, R.font.barlow_condensed_xbold_italic) ?: Typeface.DEFAULT_BOLD
    } catch (_: Exception) { Typeface.DEFAULT_BOLD }
    private val body: Typeface = try {
        ResourcesCompat.getFont(context, R.font.archivo_semibold) ?: Typeface.DEFAULT
    } catch (_: Exception) { Typeface.DEFAULT }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private lateinit var t: RideScreen.Theme
    private var lastSeenRev = -1

    /**
     * On the dash: show or hide with the saved "Ride screen on" choice, so the phone's picker and
     * the handlebar switch take effect on a live session without rebuilding anything.
     */
    var followPrefs = false

    /** The rider is looking at the full map for a moment (handlebar); the screen comes back on Back. */
    var peekMap = false
        set(v) { field = v; syncVisibility() }

    /** Told whenever the map window changes: null = map fully covered or the screen is hidden. */
    var onMapWindow: ((RectF?) -> Unit)? = null
    private var lastWindowKey = ""

    private fun syncVisibility() {
        if (!followPrefs) return
        val show = RideScreen.enabled(context) && !peekMap
        val want = if (show) VISIBLE else GONE
        if (visibility != want) visibility = want
        val w = if (show) mapWindow() else null
        val key = "$show/${w?.toShortString()}"
        if (key != lastWindowKey) { lastWindowKey = key; onMapWindow?.invoke(w) }
    }

    private val tick = object : Runnable {
        override fun run() {
            // Clock + anything that changed since the last frame; one redraw a second is plenty.
            syncVisibility()
            invalidate()
            postDelayed(this, 1000L)
        }
    }

    /** Apply a preset/theme/on-off change right away instead of on the next tick. */
    fun syncNow() { syncVisibility(); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { syncVisibility() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        postDelayed(tick, 1000L)
        syncVisibility()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // ---- values -------------------------------------------------------------------------------

    private class V(
        val speed: Int, val heading: Float?, val alt: Int?, val acc: Int?,
        val tripKm: String, val tripMin: String, val max: Int, val avg: Int, val limit: Int?,
        val cue: String?, val cueDist: String?, val street: String?,
        val temp: String, val rain: String?, val volts: Float?, val range: Int?,
        val now: String?, val phone: Int?, val lat: Double?, val lon: Double?,
    )

    private fun values(): V = if (demo) {
        V(demoSpeed, 42f, 214, 4, "9.2", "24", 71, 38, 60, "Turn right", "300 m", "Main road",
            "31°", "Rain in about 40 min", 12.6f, 140, "Track title · Artist", 99, 13.9, 100.5)
    } else {
        val d = RideData
        V(
            d.speedKmh.toInt(), d.headingDeg, d.altitudeM, d.accuracyM,
            String.format(Locale.US, "%.1f", d.tripM / 1000.0), (d.movingMs / 60_000L).toString(),
            d.maxKmh.toInt(), d.avgKmh.toInt(), d.limitKmh, d.cueText, d.cueDist, d.street,
            d.tempC?.let { "${it.toInt()}°" } ?: "—", d.rainNote,
            d.batteryV ?: try { PkeBattery.last(context)?.volts } catch (_: Exception) { null }, d.rangeKm,
            listOfNotNull(d.nowTitle, d.nowArtist).joinToString(" · ").ifBlank { null },
            d.phonePct ?: phoneBattery(), d.lat, d.lon,
        )
    }

    private fun phoneBattery(): Int? = try {
        (context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (_: Exception) { null }

    private fun clock(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    // ---- drawing helpers ----------------------------------------------------------------------

    private fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun lift(): Int = if (t.light) Color.WHITE else t.line

    private fun numText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
                        align: Paint.Align = Paint.Align.LEFT, glow: Boolean = false) {
        text.typeface = num; text.textSize = size; text.color = color; text.textAlign = align
        if (glow) text.setShadowLayer(size * 0.12f, 0f, size * 0.05f, alpha(t.accent, 110)) else text.clearShadowLayer()
        c.drawText(s, x, y, text)
        text.clearShadowLayer()
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
                      align: Paint.Align = Paint.Align.LEFT, maxW: Float = 0f) {
        text.typeface = body; text.textSize = size; text.color = color; text.textAlign = align
        text.clearShadowLayer()
        var out = s
        if (maxW > 0f && text.measureText(out) > maxW) {
            while (out.length > 1 && text.measureText("$out…") > maxW) out = out.dropLast(1)
            out = "$out…"
        }
        c.drawText(out, x, y, text)
    }

    /** Raised panel: soft drop shadow, top-to-bottom gradient, a highlight along the top edge. */
    private fun card(c: Canvas, l: Float, tp: Float, r: Float, b: Float, rad: Float = 20f) {
        fill.shader = null
        fill.color = alpha(Color.BLACK, if (t.light) 28 else 120)
        rect.set(l + 1f, tp + 8f, r - 1f, b + 10f); c.drawRoundRect(rect, rad, rad, fill)
        fill.alpha = 255; fill.shader = LinearGradient(0f, tp, 0f, b, lift(), t.surf, Shader.TileMode.CLAMP)
        rect.set(l, tp, r, b); c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        stroke.shader = null; stroke.strokeWidth = 1.5f
        stroke.color = alpha(Color.WHITE, if (t.light) 255 else 40)
        c.drawLine(l + rad, tp + 1f, r - rad, tp + 1f, stroke)
    }

    private fun accentPanel(c: Canvas, l: Float, tp: Float, r: Float, b: Float, rad: Float = 20f) {
        fill.shader = null
        fill.color = alpha(t.accent, 90)
        rect.set(l + 4f, tp + 10f, r - 4f, b + 12f); c.drawRoundRect(rect, rad, rad, fill)
        fill.alpha = 255; fill.shader = LinearGradient(l, tp, r, b, t.accent, alpha(t.accent, 205), Shader.TileMode.CLAMP)
        rect.set(l, tp, r, b); c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        stroke.strokeWidth = 2f; stroke.color = alpha(Color.WHITE, 100)
        c.drawLine(l + rad, tp + 1.5f, r - rad, tp + 1.5f, stroke)
    }

    private fun bar(c: Canvas, l: Float, y: Float, r: Float, pct: Float, color: Int, h: Float = 12f) {
        fill.shader = null; fill.color = t.line
        rect.set(l, y, r, y + h); c.drawRoundRect(rect, h / 2, h / 2, fill)
        val w = (r - l) * pct.coerceIn(0f, 1f)
        if (w > h) {
            fill.color = alpha(color, 70)
            rect.set(l, y - 3f, l + w, y + h + 3f); c.drawRoundRect(rect, h, h, fill)
            fill.color = color
            rect.set(l, y, l + w, y + h); c.drawRoundRect(rect, h / 2, h / 2, fill)
        }
    }

    private fun limitSign(c: Canvas, cx: Float, cy: Float, r: Float, limit: Int?) {
        fill.shader = null; fill.color = Color.WHITE; c.drawCircle(cx, cy, r, fill)
        stroke.strokeWidth = r * 0.2f; stroke.color = if (limit != null) 0xFFFF6B5E.toInt() else t.mute
        c.drawCircle(cx, cy, r - stroke.strokeWidth / 2, stroke)
        text.typeface = body; text.textSize = r * 0.78f; text.color = 0xFF111111.toInt(); text.textAlign = Paint.Align.CENTER
        c.drawText(limit?.toString() ?: "—", cx, cy + r * 0.28f, text)
    }

    /** Glow, diagonal texture and a perspective grid floor: depth behind the instruments. */
    private fun background(c: Canvas, clipOut: RectF? = null) {
        val save = c.save()
        if (clipOut != null && Build.VERSION.SDK_INT >= 26) c.clipOutRect(clipOut)
        fill.alpha = 255; fill.shader = LinearGradient(0f, 0f, 0f, DH * 0.65f, t.surf, t.bg, Shader.TileMode.CLAMP)
        c.drawRect(-2000f, -2000f, DW + 2000f, DH + 2000f, fill)
        fill.alpha = 255; fill.shader = RadialGradient(DW * 0.26f, DH * 0.6f, DW * 0.42f, alpha(t.accent, if (t.light) 40 else 62), alpha(t.accent, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, DW, DH, fill)
        fill.alpha = 255; fill.shader = RadialGradient(DW * 0.88f, DH * 0.1f, DW * 0.36f, alpha(t.accent2, if (t.light) 30 else 46), alpha(t.accent2, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, DW, DH, fill)
        fill.shader = null
        // Diagonal hatch.
        stroke.shader = null; stroke.strokeWidth = 2f
        stroke.color = if (t.light) alpha(Color.BLACK, 12) else alpha(Color.WHITE, 9)
        var x = -DH
        while (x < DW) { c.drawLine(x, DH, x + DH * 0.47f, 0f, stroke); x += 15f }
        // Perspective floor: lines fan out from a vanishing point, rows bunch up towards it.
        val horizon = DH - 150f
        val vx = DW / 2
        stroke.strokeWidth = 1.6f
        for (i in -14..14) {
            stroke.shader = LinearGradient(0f, horizon, 0f, DH, alpha(t.accent, 0), alpha(t.accent, 90), Shader.TileMode.CLAMP)
            c.drawLine(vx + i * 14f, horizon, vx + i * 130f, DH, stroke)
        }
        stroke.shader = null
        var row = 0
        var y = DH
        while (y > horizon + 6f && row < 9) {
            stroke.color = alpha(t.accent, (90 * (y - horizon) / 150f).toInt())
            c.drawLine(0f, y, DW, y, stroke)
            y -= 34f * Math.pow(0.72, row.toDouble()).toFloat()
            row++
        }
        c.restoreToCount(save)
    }

    private fun pol(cx: Float, cy: Float, r: Float, deg: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return (cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat()
    }

    /** 270° speedometer arc: track, red zone, lit part with glow, ticks and numbers. */
    private fun speedArc(c: Canvas, cx: Float, cy: Float, r: Float, speed: Int, track: Float = 26f) {
        fill.shader = null; fill.color = t.surf; c.drawCircle(cx, cy, r - track / 2 - 4f, fill)
        stroke.shader = null; stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = 2f; stroke.color = t.line; c.drawCircle(cx, cy, r - track / 2 - 4f, stroke)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeWidth = track; stroke.color = t.line
        c.drawArc(rect, 135f, 270f, false, stroke)
        stroke.color = alpha(0xFFFF6B5E.toInt(), 115)
        c.drawArc(rect, 135f + 270f * 130f / MAX_SPEED, 270f * 30f / MAX_SPEED, false, stroke)
        val sweep = 270f * speed.coerceIn(0, MAX_SPEED.toInt()) / MAX_SPEED
        if (sweep > 0.5f) {
            stroke.strokeWidth = track + 18f; stroke.color = alpha(t.accent, 56)
            c.drawArc(rect, 135f, sweep, false, stroke)
            stroke.strokeWidth = track; stroke.color = t.accent
            c.drawArc(rect, 135f, sweep, false, stroke)
        }
        stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeWidth = 3f; stroke.color = t.ink
        var v = 0
        while (v <= MAX_SPEED.toInt()) {
            val a = 135f + 270f * v / MAX_SPEED
            val r1 = r - track / 2 - 8f
            val r0 = r1 - if (v % 20 == 0) 14f else 7f
            val (x0, y0) = pol(cx, cy, r0, a); val (x1, y1) = pol(cx, cy, r1, a)
            c.drawLine(x0, y0, x1, y1, stroke)
            if (v % 20 == 0) {
                val (lx, ly) = pol(cx, cy, r - track / 2 - 40f, a)
                numText(c, v.toString(), lx, ly + 7f, 20f, if (v >= 140) 0xFFFF6B5E.toInt() else t.mute, Paint.Align.CENTER)
            }
            v += 10
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /** Compass that turns with the bike: the heading is always at the top marker. */
    private fun compass(c: Canvas, cx: Float, cy: Float, r: Float, heading: Float?) {
        val h = heading ?: 0f
        fill.shader = null; fill.color = t.surf; c.drawCircle(cx, cy, r, fill)
        stroke.shader = null; stroke.strokeWidth = 10f; stroke.color = t.line; c.drawCircle(cx, cy, r, stroke)
        stroke.strokeWidth = 2.5f; stroke.strokeCap = Paint.Cap.ROUND; stroke.color = t.mute
        var a = 0
        while (a < 360) {
            val r0 = r - if (a % 45 == 0) 16f else 9f
            val (x0, y0) = pol(cx, cy, r0, a - 90f - h); val (x1, y1) = pol(cx, cy, r - 5f, a - 90f - h)
            c.drawLine(x0, y0, x1, y1, stroke)
            a += 15
        }
        stroke.strokeCap = Paint.Cap.BUTT
        for ((deg, l) in listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")) {
            val (x, y) = pol(cx, cy, r - 32f, deg - 90f - h)
            numText(c, l, x, y + r * 0.09f, r * 0.26f, if (l == "N") t.accent else t.ink, Paint.Align.CENTER)
        }
        path.reset(); path.moveTo(cx, cy - r - 2f); path.rLineTo(-11f, -18f); path.rLineTo(22f, 0f); path.close()
        fill.color = t.accent2; c.drawPath(path, fill)
        numText(c, RideData.headingLabel(heading), cx, cy + r * 0.08f, r * 0.58f, t.ink, Paint.Align.CENTER)
        label(c, heading?.let { "${it.toInt()}°" } ?: "no heading yet", cx, cy + r * 0.34f, r * 0.17f, t.mute, Paint.Align.CENTER)
    }

    private fun barCard(c: Canvas, l: Float, tp: Float, r: Float, b: Float, name: String, value: String, pct: Float?, color: Int) {
        card(c, l, tp, r, b)
        val mid = (tp + b) / 2
        label(c, name, l + 18f, mid - 10f, 15f, t.mute)
        numText(c, value, r - 18f, mid - 4f, 36f, t.ink, Paint.Align.RIGHT)
        bar(c, l + 18f, mid + 14f, r - 18f, pct ?: 0f, color)
    }

    // ---- presets ------------------------------------------------------------------------------

    private fun drawCruise(c: Canvas, v: V) {
        background(c)
        fill.shader = null; fill.color = 0xFF5BD47A.toInt(); c.drawCircle(33f, 34f, 5f, fill)
        label(c, "Cruise", 48f, 40f, 18f, t.mute)
        // Three digits need a smaller size to stay clear of the tiles.
        val big = if (v.speed >= 100) 215f else 290f
        numText(c, v.speed.toString(), 24f, 326f, big, t.ink, glow = true)
        text.typeface = num; text.textSize = big
        val sw = text.measureText(v.speed.toString())
        numText(c, "KM/H", 24f + sw + 14f, 326f, if (v.speed >= 100) 34f else 44f, t.accent)
        limitSign(c, 50f, 379f, 22f, v.limit)
        label(c, if (v.limit != null) "Limit, from the map" else "No limit data here", 84f, 386f, 18f, t.mute)

        val x0 = 452f; val x1 = 996f; val gap = 12f
        val tw = (x1 - x0 - gap) / 2; val th = 134f
        fun tile(col: Int, row: Int, value: String, sub: String, color: Int) {
            val l = x0 + col * (tw + gap); val tp = 24f + row * (th + gap)
            card(c, l, tp, l + tw, tp + th, 22f)
            numText(c, value, l + 20f, tp + 78f, 64f, color)
            label(c, sub, l + 20f, tp + 108f, 17f, t.mute, maxW = tw - 40f)
        }
        tile(0, 0, "${v.tripKm} KM", "Trip · ${v.tripMin} min", t.accent)
        tile(1, 0, clock(), "Max ${v.max} · avg ${v.avg} km/h", t.ink)
        tile(0, 1, v.temp, v.rain ?: "Weather", t.accent2)
        tile(1, 1, v.volts?.let { String.format(Locale.US, "%.1f V", it) } ?: "—", "Bike battery", t.accent2)

        val sy = 24f + 2 * (th + gap)
        accentPanel(c, x0, sy, x1, DH - 24f, 22f)
        val line = v.now ?: v.cue?.let { "${v.cueDist ?: ""}  $it".trim() } ?: "Heading ${RideData.headingLabel(v.heading)}"
        text.typeface = body; text.textSize = 22f; text.color = t.onAccent; text.textAlign = Paint.Align.LEFT
        label(c, line, x0 + 22f, (sy + DH - 24f) / 2 + 8f, 22f, t.onAccent, maxW = x1 - x0 - 44f)
    }

    private fun drawStreet(c: Canvas, v: V) {
        background(c)
        card(c, 24f, 20f, 286f, 297f, 22f)
        compass(c, 155f, 164f, 96f, v.heading)
        barCard(c, 24f, 309f, 286f, 405f, "Trip · ${v.tripMin} min", "${v.tripKm} KM", (v.tripKm.toFloatOrNull() ?: 0f) / 50f, t.accent2)

        speedArc(c, 512f, 225f, 170f, v.speed)
        numText(c, v.speed.toString(), 512f, 262f, 150f, t.ink, Paint.Align.CENTER, glow = true)
        numText(c, "KM/H", 512f, 296f, 26f, t.accent, Paint.Align.CENTER)
        limitSign(c, 512f, 388f, 27f, v.limit)

        val l = 738f; val r = 1000f; val h = (385f - 24f) / 3
        barCard(c, l, 20f, r, 20f + h, "Fuel range", v.range?.let { "$it KM" } ?: "—", v.range?.let { it / 260f }, t.accent)
        barCard(c, l, 32f + h, r, 32f + 2 * h, "Bike battery", v.volts?.let { String.format(Locale.US, "%.1f V", it) } ?: "—",
            v.volts?.let { (it - 11f) / 3.5f }, 0xFF5BD47A.toInt())
        card(c, l, 44f + 2 * h, r, 44f + 3 * h)
        val mid = 44f + 2.5f * h
        label(c, v.rain ?: "Weather", l + 18f, mid + 22f, 15f, t.mute, maxW = r - l - 36f)
        numText(c, v.temp, l + 18f, mid - 2f, 46f, t.accent2)
        numText(c, clock(), r - 18f, mid - 2f, 46f, t.ink, Paint.Align.RIGHT)
    }

    /** Map window of the hub preset, in design coordinates. */
    private val hubHole = RectF(302f, 60f, 760f, 359f)

    private fun drawHub(c: Canvas, v: V) {
        background(c, clipOut = if (demo) null else hubHole)
        if (demo) {
            // No map under a preview: stand-in so the layout still reads.
            fill.shader = null; fill.color = 0xFFDCE6EC.toInt(); c.drawRoundRect(hubHole, 18f, 18f, fill)
            stroke.shader = null; stroke.strokeWidth = 16f; stroke.color = 0xFFE0527A.toInt()
            path.reset(); path.moveTo(640f, 60f); path.cubicTo(630f, 140f, 560f, 180f, 531f, 210f); path.cubicTo(500f, 250f, 470f, 320f, 462f, 359f)
            c.drawPath(path, stroke)
            fill.color = t.accent; c.drawCircle(531f, 210f, 20f, fill)
        }
        stroke.shader = null; stroke.strokeWidth = 3f; stroke.color = t.line
        c.drawRoundRect(hubHole, 6f, 6f, stroke)

        accentPanel(c, 20f, 18f, 50f, 48f, 8f)
        numText(c, "R", 35f, 42f, 24f, t.onAccent, Paint.Align.CENTER)
        numText(c, "RIDE HUB", 62f, 43f, 28f, t.ink)
        numText(c, clock(), 1004f, 44f, 32f, t.ink, Paint.Align.RIGHT)
        val status = listOfNotNull(v.temp.takeIf { it != "—" }, v.rain).joinToString(" · ")
        label(c, status, 900f, 40f, 15f, t.mute, Paint.Align.RIGHT, maxW = 330f)
        fill.shader = null; fill.color = if (v.acc != null) 0xFF5BD47A.toInt() else t.mute
        c.drawCircle(446f, 34f, 5f, fill)
        label(c, if (v.acc != null) "GPS lock" else "No GPS fix", 458f, 40f, 15f, t.ink)

        card(c, 20f, 60f, 290f, 237f)
        label(c, "CURRENT LOCATION", 36f, 92f, 13f, t.mute)
        label(c, v.street ?: "—", 36f, 126f, 22f, t.ink, maxW = 238f)
        val pos = if (v.lat != null && v.lon != null) String.format(Locale.US, "%.4f, %.4f", v.lat, v.lon) else "waiting for GPS"
        label(c, pos, 36f, 152f, 15f, t.mute, maxW = 238f)
        label(c, v.cue?.let { "${v.cueDist ?: ""}  $it".trim() } ?: "No route set", 36f, 210f, 16f, t.accent2, maxW = 238f)

        card(c, 20f, 249f, 290f, 359f)
        numText(c, v.speed.toString(), 65f, 314f, 56f, t.accent, Paint.Align.CENTER)
        label(c, "LIVE", 65f, 340f, 13f, t.mute, Paint.Align.CENTER)
        numText(c, v.avg.toString(), 155f, 314f, 56f, t.accent2, Paint.Align.CENTER)
        label(c, "AVG", 155f, 340f, 13f, t.mute, Paint.Align.CENTER)
        limitSign(c, 245f, 296f, 25f, v.limit)
        label(c, "LIMIT", 245f, 340f, 13f, t.mute, Paint.Align.CENTER)

        // Sun and daylight.
        card(c, 772f, 60f, 1004f, 359f)
        label(c, "SUN AND DAYLIGHT", 788f, 88f, 13f, t.mute)
        // Preview: fixed sample day, so it reads the same whatever the phone's clock and time zone.
        val sun = if (demo) (376 to 1066) else if (v.lat != null && v.lon != null) RideData.sunTimes(v.lat, v.lon) else null
        val nowMin = if (demo) 844 else Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        rect.set(800f, 118f, 976f, 118f + 176f)
        stroke.strokeWidth = 5f; stroke.strokeCap = Paint.Cap.ROUND; stroke.color = t.line
        c.drawArc(rect, 180f, 180f, false, stroke)
        if (sun != null && sun.second > sun.first) {
            val p = ((nowMin - sun.first).toFloat() / (sun.second - sun.first)).coerceIn(0f, 1f)
            stroke.color = t.accent
            c.drawArc(rect, 180f, 180f * p, false, stroke)
            val (sx, sy) = pol(888f, 206f, 88f, 180f + 180f * p)
            fill.shader = null; fill.color = t.accent; c.drawCircle(sx, sy, 11f, fill)
        }
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeWidth = 2f; stroke.color = t.mute
        c.drawLine(790f, 206f, 986f, 206f, stroke)
        fun hm(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
        label(c, "RISE", 788f, 246f, 12f, t.mute)
        numText(c, sun?.let { hm(it.first) } ?: "—", 788f, 282f, 36f, t.ink)
        label(c, "SET", 988f, 246f, 12f, t.mute, Paint.Align.RIGHT)
        numText(c, sun?.let { hm(it.second) } ?: "—", 988f, 282f, 36f, t.accent2, Paint.Align.RIGHT)
        val left = sun?.let { it.second - nowMin }
        label(c, when {
            left == null -> "Sun times need a GPS fix"
            left > 0 -> "${left / 60} h ${left % 60} min of daylight left"
            else -> "After sunset"
        }, 788f, 336f, 14f, t.accent, maxW = 200f)

        card(c, 20f, 369f, 1004f, 409f, 14f)
        val cells = listOf(
            Triple("GPS", v.acc?.let { "± $it m" } ?: "—", t.accent),
            Triple("PHONE", v.phone?.let { "$it %" } ?: "—", 0xFF5BD47A.toInt()),
            Triple("RANGE", v.range?.let { "~$it km" } ?: "—", t.accent),
            Triple("BATTERY", v.volts?.let { String.format(Locale.US, "%.1f V", it) } ?: "—", 0xFF5BD47A.toInt()),
            Triple("TRIP", "${v.tripKm} km", t.accent2),
        )
        val cw = (1004f - 20f) / cells.size
        cells.forEachIndexed { i, (name, value, color) ->
            val l = 20f + i * cw
            label(c, name, l + 16f, 394f, 13f, t.mute)
            label(c, value, l + cw - 16f, 395f, 16f, color, Paint.Align.RIGHT)
            if (i > 0) { stroke.strokeWidth = 1f; stroke.color = t.line; c.drawLine(l, 377f, l, 401f, stroke) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        t = themeOverride ?: RideScreen.theme(context)
        val preset = presetOverride ?: RideScreen.preset(context)
        lastSeenRev = RideData.revision
        val s = min(width / DW, height / DH)
        val save = canvas.save()
        canvas.translate((width - DW * s) / 2f, (height - DH * s) / 2f)
        canvas.scale(s, s)
        val v = values()
        when (preset) {
            RideScreen.Preset.CRUISE -> drawCruise(canvas, v)
            RideScreen.Preset.STREET -> drawStreet(canvas, v)
            RideScreen.Preset.HUB -> drawHub(canvas, v)
        }
        canvas.restoreToCount(save)
    }

    /** Where the live map shows through for the current preset, in view pixels; null = fully covered. */
    fun mapWindow(): RectF? {
        val preset = presetOverride ?: RideScreen.preset(context)
        if (!preset.mapWindow || width == 0 || height == 0) return null
        val s = min(width / DW, height / DH)
        val ox = (width - DW * s) / 2f; val oy = (height - DH * s) / 2f
        return RectF(ox + hubHole.left * s, oy + hubHole.top * s, ox + hubHole.right * s, oy + hubHole.bottom * s)
    }

    /** Redraw now if the data or the chosen preset/theme changed (called on every GPS fix). */
    fun refresh() {
        if (RideData.revision != lastSeenRev) invalidate() else postInvalidateOnAnimation()
    }

    companion object {
        const val DW = 1024f
        const val DH = 425f
        private const val MAX_SPEED = 160f
    }
}
