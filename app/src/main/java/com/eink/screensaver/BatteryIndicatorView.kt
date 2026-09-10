package com.eink.screensaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A battery outline whose fill tracks [level].
 *
 * Drawn on a canvas rather than shipped as a set of drawables so the fill is
 * continuous instead of stepped, and in pure black on white with no gradient,
 * like everything else on this screen.
 *
 * The view has no intrinsic size worth guessing at — give it explicit width and
 * height in the layout.
 */
class BatteryIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val strokeWidth = 1.2f * density
    private val nubWidth = 1.6f * density
    private val corner = 1.5f * density
    private val innerGap = 1.2f * density

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = this@BatteryIndicatorView.strokeWidth
    }

    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private val body = RectF()
    private val nub = RectF()
    private val charge = RectF()

    /** Charge in percent, 0..100. */
    var level: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, 100)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val half = strokeWidth / 2f
        body.set(half, half, w - nubWidth - half, h - half)
        canvas.drawRoundRect(body, corner, corner, outline)

        // The terminal on the right-hand end, drawn solid so it reads at this size.
        val nubHeight = h * 0.42f
        nub.set(w - nubWidth, (h - nubHeight) / 2f, w, (h + nubHeight) / 2f)
        canvas.drawRoundRect(nub, corner / 2f, corner / 2f, solid)

        if (level <= 0) return

        val trackLeft = body.left + half + innerGap
        val trackRight = body.right - half - innerGap
        val trackTop = body.top + half + innerGap
        val trackBottom = body.bottom - half - innerGap
        val filled = (trackRight - trackLeft) * (level / 100f)
        // Below a couple of percent the fill would be thinner than a pixel, so
        // round it up to something the panel can actually show.
        val minFill = innerGap
        charge.set(trackLeft, trackTop, trackLeft + maxOf(filled, minFill), trackBottom)
        canvas.drawRect(charge, solid)
    }
}
