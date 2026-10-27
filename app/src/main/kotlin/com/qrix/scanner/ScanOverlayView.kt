package com.qrix.scanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Drawn on top of the camera frame: white rounded corner brackets and a glowing
 * scan line that sweeps up and down. The view is exactly the size of the frame.
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val stroke = 4.5f * d
    private val radius = 28f * d

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val corners = Path()
    private val lineRect = RectF()
    private var progress = 0f

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val half = stroke / 2f
        box.set(half, half, w - half, h - half)

        val l = box.left; val t = box.top; val r = box.right; val b = box.bottom
        val len = 40f * d
        val rad = radius
        corners.reset()
        corners.moveTo(l, t + len); corners.lineTo(l, t + rad)
        corners.arcTo(RectF(l, t, l + 2 * rad, t + 2 * rad), 180f, 90f, false); corners.lineTo(l + len, t)
        corners.moveTo(r - len, t); corners.lineTo(r - rad, t)
        corners.arcTo(RectF(r - 2 * rad, t, r, t + 2 * rad), 270f, 90f, false); corners.lineTo(r, t + len)
        corners.moveTo(r, b - len); corners.lineTo(r, b - rad)
        corners.arcTo(RectF(r - 2 * rad, b - 2 * rad, r, b), 0f, 90f, false); corners.lineTo(r - len, b)
        corners.moveTo(l + len, b); corners.lineTo(l + rad, b)
        corners.arcTo(RectF(l, b - 2 * rad, l + 2 * rad, b), 90f, 90f, false); corners.lineTo(l, b - len)

        val lineColor = ContextCompat.getColor(context, R.color.qx_scan_line)
        linePaint.color = lineColor
        glowPaint.color = (lineColor and 0x00FFFFFF) or 0x55000000
        cornerPaint.color = ContextCompat.getColor(context, R.color.qx_scan_corner)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(corners, cornerPaint)

        val inset = 22f * d
        val travel = height - 2 * inset - 2 * stroke
        val y = inset + stroke + progress * travel
        lineRect.set(inset, y - 9 * d, width - inset, y + 9 * d)
        canvas.drawRoundRect(lineRect, 9 * d, 9 * d, glowPaint)
        lineRect.set(inset, y - 1.5f * d, width - inset, y + 1.5f * d)
        canvas.drawRoundRect(lineRect, 2 * d, 2 * d, linePaint)
    }
}
