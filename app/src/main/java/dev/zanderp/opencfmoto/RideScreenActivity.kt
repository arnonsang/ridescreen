// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton

/**
 * "Dash screen": choose what the bike shows — Android Auto or one of the app's own ride screens —
 * with a live preview of the chosen layout and colour theme. Changes apply to a running session at
 * once; the buttons at the bottom start one.
 */
class RideScreenActivity : AppCompatActivity() {

    private lateinit var preview: RideScreenView
    private lateinit var modeAa: TextView
    private lateinit var modeRide: TextView
    private lateinit var modeMirror: TextView
    private val optionRows = ArrayList<Pair<TextView, () -> String>>()
    private lateinit var go: MaterialButton
    private lateinit var nowRow: TextView
    private lateinit var autoRow: TextView
    private val presetViews = HashMap<RideScreen.Preset, TextView>()
    private val themeViews = HashMap<RideScreen.Theme, View>()
    private val accentViews = HashMap<Int, TextView>()

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg))
            isFillViewport = true
            addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(SafeInsets.BARS)
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        col.addView(TextView(this).apply {
            text = getString(R.string.ride_title)
            textSize = 30f
            setTextColor(color(R.color.text_primary))
            typeface = ResourcesCompat.getFont(this@RideScreenActivity, R.font.archivo_black)
        })
        col.addView(sub(getString(R.string.ride_intro)), lp(top = 4))

        // Preview at the dash's own proportions.
        preview = RideScreenView(this).apply { demo = true }
        val frame = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                val height = (width * RideScreenView.DH / RideScreenView.DW).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }.apply {
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(0xFF000000.toInt()) }
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        col.addView(frame, lp(top = 14))
        col.addView(sub(getString(R.string.ride_preview_note)), lp(top = 6))

        // Android Auto / Ride screen.
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = 28 * dp; setColor(color(R.color.surface)) }
            setPadding(px(4), px(4), px(4), px(4))
        }
        modeAa = segment(getString(R.string.ride_mode_aa)) { setMode(RideScreen.Mode.AA) }
        modeRide = segment(getString(R.string.ride_mode_ride)) { setMode(RideScreen.Mode.RIDE) }
        modeMirror = segment(getString(R.string.ride_mode_mirror)) { setMode(RideScreen.Mode.MIRROR) }
        seg.addView(modeAa, LinearLayout.LayoutParams(0, px(48), 1f))
        seg.addView(modeRide, LinearLayout.LayoutParams(0, px(48), 1f))
        seg.addView(modeMirror, LinearLayout.LayoutParams(0, px(48), 1f))
        col.addView(seg, lp(top = 18))

        col.addView(head(getString(R.string.ride_layout)), lp(top = 20))
        RideScreen.Preset.entries.chunked(4).forEachIndexed { rowIndex, rowPresets ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowPresets.forEachIndexed { i, p ->
                val v = chip(p.label) { RideScreen.setPreset(this, p); render() }
                presetViews[p] = v
                row.addView(v, LinearLayout.LayoutParams(0, px(52), 1f).apply { if (i > 0) marginStart = px(8) })
            }
            // Keep a short last row aligned with the columns above.
            repeat(4 - rowPresets.size) { row.addView(View(this), LinearLayout.LayoutParams(0, px(52), 1f).apply { marginStart = px(8) }) }
            col.addView(row, lp(top = if (rowIndex == 0) 8 else 8))
        }

        col.addView(head(getString(R.string.ride_theme)), lp(top = 20))
        val themes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        RideScreen.Theme.entries.forEachIndexed { i, t ->
            val sw = View(this).apply {
                contentDescription = t.label
                isClickable = true; isFocusable = true
                setOnClickListener { RideScreen.setTheme(this@RideScreenActivity, t); render() }
            }
            themeViews[t] = sw
            themes.addView(sw, LinearLayout.LayoutParams(px(48), px(48)).apply { if (i > 0) marginStart = px(12) })
        }
        col.addView(themes, lp(top = 8))

        col.addView(head(getString(R.string.ride_options)), lp(top = 20))
        nowRow = optionRow(getString(R.string.ride_now_playing)) {
            if (RideFeeds.NowPlaying.hasAccess(this)) return@optionRow
            try { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
        }
        col.addView(nowRow, lp(top = 8))
        autoRow = optionRow(getString(R.string.ride_auto_disconnect)) {
            AppSettings.setAutoDisconnect(this, !AppSettings.autoDisconnect(this)); render()
        }
        col.addView(autoRow, lp(top = 8))

        // Each row shows its current value and changes it on tap.
        fun row(label: String, value: () -> String, onTap: () -> Unit) {
            val v = optionRow(label) { onTap(); render() }
            optionRows += v to { label + "  ·  " + value() }
            col.addView(v, lp(top = 8))
        }
        fun onOff(b: Boolean) = getString(if (b) R.string.ride_on else R.string.ride_off)
        row(getString(R.string.ride_alert), {
            RideScreen.alertMargin(this).let { if (it == 0) getString(R.string.ride_off) else getString(R.string.ride_alert_over, it) }
        }) {
            val steps = intArrayOf(0, 5, 10, 15, 20)
            RideScreen.setAlertMargin(this, steps[(steps.indexOf(RideScreen.alertMargin(this)).coerceAtLeast(0) + 1) % steps.size])
        }
        row(getString(R.string.ride_alert_beep), { onOff(RideScreen.alertBeep(this)) }) {
            RideScreen.setAlertBeep(this, !RideScreen.alertBeep(this))
        }
        row(getString(R.string.ride_auto_theme), { onOff(RideScreen.autoTheme(this)) }) {
            RideScreen.setAutoTheme(this, !RideScreen.autoTheme(this))
        }
        row(getString(R.string.ride_night_dim), { onOff(RideScreen.nightDim(this)) }) {
            RideScreen.setNightDim(this, !RideScreen.nightDim(this))
        }

        col.addView(head(getString(R.string.ride_accent)), lp(top = 20))
        val accents = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        RideScreen.ACCENTS.forEachIndexed { i, c ->
            val sw = TextView(this).apply {
                contentDescription = if (c == 0) getString(R.string.ride_accent_theme) else String.format("#%06X", c and 0xFFFFFF)
                gravity = Gravity.CENTER; textSize = 11f
                if (c == 0) text = "A"
                isClickable = true; isFocusable = true
                setOnClickListener { RideScreen.setCustomAccent(this@RideScreenActivity, c); render() }
            }
            accentViews[c] = sw
            accents.addView(sw, LinearLayout.LayoutParams(0, px(40), 1f).apply { if (i > 0) marginStart = px(6) })
        }
        col.addView(accents, lp(top = 8))

        col.addView(head(getString(R.string.ride_show)), lp(top = 20))
        RideScreen.Field.entries.forEach { f ->
            row(f.label, { getString(if (f.key in RideScreen.hidden(this)) R.string.ride_hidden else R.string.ride_shown) }) {
                RideScreen.setHidden(this, f, f.key !in RideScreen.hidden(this))
            }
        }

        col.addView(sub(getString(R.string.ride_keys)), lp(top = 20))

        go = MaterialButton(this).apply {
            textSize = 16f
            cornerRadius = px(28)
            minimumHeight = px(60)
            setOnClickListener { start() }
        }
        col.addView(go, lp(top = 22))

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px(top) }

    private fun sub(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(color(R.color.text_secondary))
    }

    private fun head(s: String) = TextView(this).apply {
        text = s.uppercase(); textSize = 12f; letterSpacing = 0.08f
        setTextColor(color(R.color.text_secondary))
    }

    private fun segment(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        typeface = ResourcesCompat.getFont(this@RideScreenActivity, R.font.archivo_semibold)
        isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun chip(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
        typeface = ResourcesCompat.getFont(this@RideScreenActivity, R.font.archivo_semibold)
        isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
    }

    /** A tappable row: label on the left, its state ("On", "Off", "Allow…") on the right. */
    private fun optionRow(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f
        setTextColor(color(R.color.text_primary))
        gravity = Gravity.CENTER_VERTICAL
        minHeight = px(52)
        setPadding(px(16), px(8), px(16), px(8))
        background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(color(R.color.surface)) }
        isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun setMode(m: RideScreen.Mode) {
        RideScreen.setMode(this, m)
        render()
    }

    private fun render() {
        val ride = RideScreen.enabled(this)
        val accent = color(R.color.brand_orange)
        fun paintSeg(v: TextView, on: Boolean) {
            v.background = if (on) GradientDrawable().apply { cornerRadius = 24 * dp; setColor(accent) } else null
            v.setTextColor(color(if (on) R.color.on_brand else R.color.text_secondary))
        }
        val mode = RideScreen.mode(this)
        paintSeg(modeAa, mode == RideScreen.Mode.AA); paintSeg(modeRide, mode == RideScreen.Mode.RIDE)
        paintSeg(modeMirror, mode == RideScreen.Mode.MIRROR)
        optionRows.forEach { (v, text) -> v.text = text() }
        val own = RideScreen.customAccent(this)
        accentViews.forEach { (c, v) ->
            v.background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(if (c == 0) color(R.color.surface) else c)
                setStroke(px(3), if (c == own) color(R.color.text_primary) else color(R.color.bg))
            }
            v.setTextColor(color(R.color.text_secondary))
        }

        val preset = RideScreen.preset(this)
        presetViews.forEach { (p, v) ->
            val on = p == preset
            v.background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                setColor(color(R.color.surface))
                setStroke(px(2), if (on) accent else color(R.color.outline))
            }
            v.setTextColor(color(if (on) R.color.text_primary else R.color.text_secondary))
            v.alpha = if (ride) 1f else 0.5f
        }
        val theme = RideScreen.theme(this)
        themeViews.forEach { (t, v) ->
            v.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(t.accent, t.accent, t.accent2, t.accent2),
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(px(3), if (t == theme) color(R.color.text_primary) else color(R.color.bg))
            }
            v.alpha = if (ride) 1f else 0.5f
        }
        preview.presetOverride = preset
        preview.themeOverride = theme
        preview.alpha = if (ride) 1f else 0.45f
        go.text = getString(
            when (mode) {
                RideScreen.Mode.RIDE -> R.string.ride_go_ride
                RideScreen.Mode.MIRROR -> R.string.ride_go_mirror
                RideScreen.Mode.AA -> R.string.ride_go_aa
            },
        )
        nowRow.text = getString(R.string.ride_now_playing) + "  ·  " +
            getString(if (RideFeeds.NowPlaying.hasAccess(this)) R.string.ride_on else R.string.ride_allow)
        autoRow.text = getString(R.string.ride_auto_disconnect) + "  ·  " +
            getString(if (AppSettings.autoDisconnect(this)) R.string.ride_on else R.string.ride_off)
    }

    /**
     * Put the choice on the dash. Ride screen = a free-ride map session with the layout on top
     * (MainActivity joins the bike, or soft-switches a live Android Auto session). Android Auto =
     * the normal connect with the saved bike.
     */
    private fun start() {
        if (RideScreen.mode(this) == RideScreen.Mode.MIRROR) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_START_MIRROR, true)
                },
            )
            finish()
            return
        }
        val ride = RideScreen.enabled(this)
        if (ride) {
            ProjectionHolder.projection?.let { try { it.stop() } catch (_: Exception) {} }
            ProjectionHolder.projection = null
            if (!GpxSession.active) GpxSession.prepareFreeRide()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_START_GPX, true)
                    putExtra(MainActivity.EXTRA_GPX_TO_BIKE, true)
                },
            )
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(AndroidAutoService.EXTRA_RESUME, true)
                },
            )
        }
        finish()
    }

    companion object {
        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, RideScreenActivity::class.java))
    }
}
