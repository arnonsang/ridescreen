// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * The three things a connection needs, as a segmented bar on the home status card:
 * Android Auto → bike Wi-Fi → dash. Each segment shows what is actually up right now (not a guess
 * from the phase), so a stuck connect tells the rider *which* leg is missing.
 */
class ConnectStepsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Step { TODO, ACTIVE, DONE, FAILED }

    private val dp = resources.displayMetrics.density
    private val labels = arrayOf(
        context.getString(R.string.steps_android_auto),
        context.getString(R.string.steps_bike_wifi),
        context.getString(R.string.steps_dash),
    )
    private var steps = arrayOf(Step.TODO, Step.TODO, Step.TODO)

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.LEFT
        try { typeface = ResourcesCompat.getFont(context, R.font.archivo_semibold) } catch (_: Exception) {}
    }
    private val rect = RectF()

    private val cTodo = ContextCompat.getColor(context, R.color.outline)
    private val cActive = ContextCompat.getColor(context, R.color.brand_orange)
    private val cDone = ContextCompat.getColor(context, R.color.status_live)
    private val cFailed = ContextCompat.getColor(context, R.color.status_error)
    private val cLabel = ContextCompat.getColor(context, R.color.text_secondary)
    private val cLabelOn = ContextCompat.getColor(context, R.color.text_primary)

    /**
     * [aa], [wifi], [dash]: is that leg up. The first leg that is not up is drawn as in progress
     * while [busy], as failed when [failed], otherwise as still to do.
     */
    fun set(aa: Boolean, wifi: Boolean, dash: Boolean, busy: Boolean, failed: Boolean) {
        val up = booleanArrayOf(aa, wifi, dash)
        var firstMissingTaken = false
        val next = Array(3) { i ->
            when {
                up[i] -> Step.DONE
                !firstMissingTaken -> {
                    firstMissingTaken = true
                    if (failed) Step.FAILED else if (busy) Step.ACTIVE else Step.TODO
                }
                else -> Step.TODO
            }
        }
        if (!next.contentEquals(steps)) {
            steps = next
            contentDescription = labels.indices.joinToString(", ") { "${labels[it]}: ${steps[it].name.lowercase()}" }
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (4 * dp + 8 * dp + text.textSize + 4 * dp).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val gap = 6 * dp
        val w = (width - gap * 2) / 3f
        val barH = 4 * dp
        var pulsing = false
        for (i in 0..2) {
            val x = i * (w + gap)
            bar.color = when (steps[i]) {
                Step.DONE -> cDone
                Step.ACTIVE -> cActive
                Step.FAILED -> cFailed
                Step.TODO -> cTodo
            }
            bar.alpha = 255
            if (steps[i] == Step.ACTIVE) {
                // Slow breathing so "working on it" reads at a glance without a spinner per step.
                val t = (System.currentTimeMillis() % 1400L) / 1400f
                bar.alpha = (110 + 145 * kotlin.math.abs(1f - 2f * t)).toInt()
                pulsing = true
            }
            rect.set(x, 0f, x + w, barH)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, bar)
            text.color = if (steps[i] == Step.TODO) cLabel else cLabelOn
            val label = android.text.TextUtils.ellipsize(labels[i], android.text.TextPaint(text), w, android.text.TextUtils.TruncateAt.END)
            canvas.drawText(label, 0, label.length, x, barH + 8 * dp + text.textSize * 0.8f, text)
        }
        if (pulsing && isShown) postInvalidateDelayed(60)
    }
}
