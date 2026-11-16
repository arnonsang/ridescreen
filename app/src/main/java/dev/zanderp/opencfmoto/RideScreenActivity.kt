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
    private lateinit var go: MaterialButton
    private val presetViews = HashMap<RideScreen.Preset, TextView>()
    private val themeViews = HashMap<RideScreen.Theme, View>()

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
        modeAa = segment(getString(R.string.ride_mode_aa)) { setMode(false) }
        modeRide = segment(getString(R.string.ride_mode_ride)) { setMode(true) }
        seg.addView(modeAa, LinearLayout.LayoutParams(0, px(48), 1f))
        seg.addView(modeRide, LinearLayout.LayoutParams(0, px(48), 1f))
        col.addView(seg, lp(top = 18))

        col.addView(head(getString(R.string.ride_layout)), lp(top = 20))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        RideScreen.Preset.entries.forEachIndexed { i, p ->
            val v = chip(p.label) { RideScreen.setPreset(this, p); render() }
            presetViews[p] = v
            presets.addView(v, LinearLayout.LayoutParams(0, px(56), 1f).apply { if (i > 0) marginStart = px(8) })
        }
        col.addView(presets, lp(top = 8))

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
        text = label; textSize = 14f; gravity = Gravity.CENTER
        typeface = ResourcesCompat.getFont(this@RideScreenActivity, R.font.archivo_semibold)
        isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
    }

    private fun setMode(ride: Boolean) {
        RideScreen.setEnabled(this, ride)
        render()
    }

    private fun render() {
        val ride = RideScreen.enabled(this)
        val accent = color(R.color.brand_orange)
        fun paintSeg(v: TextView, on: Boolean) {
            v.background = if (on) GradientDrawable().apply { cornerRadius = 24 * dp; setColor(accent) } else null
            v.setTextColor(color(if (on) R.color.on_brand else R.color.text_secondary))
        }
        paintSeg(modeAa, !ride); paintSeg(modeRide, ride)

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
        go.text = getString(if (ride) R.string.ride_go_ride else R.string.ride_go_aa)
    }

    /**
     * Put the choice on the dash. Ride screen = a free-ride map session with the layout on top
     * (MainActivity joins the bike, or soft-switches a live Android Auto session). Android Auto =
     * the normal connect with the saved bike.
     */
    private fun start() {
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
