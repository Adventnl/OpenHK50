package org.openhk50.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils

@SuppressLint("ViewConstructor")
internal class MeasurementProgress(context: Context, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var animator: ValueAnimator? = null
    private var measuring = false
    private var visible = false
    private var progress = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setMeasuring(active: Boolean) {
        if (measuring == active) return
        measuring = active
        updateAnimation()
        invalidate()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        visible = false
        updateAnimation()
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (!measuring || !visible || !ValueAnimator.areAnimatorsEnabled()) {
            animator?.cancel()
            animator = null
        } else if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_650
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    progress = it.animatedFraction
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!measuring) return
        val inset = paint.strokeWidth / 2
        val length = (width - 2 * inset).coerceAtLeast(0f)
        val y = height / 2f
        paint.color = ColorUtils.setAlphaComponent(accent, 75)
        canvas.drawLine(inset, y, inset + length, y, paint)
        val start = if (animator == null) .38f else progress * 1.25f - .25f
        paint.color = accent
        canvas.drawLine(inset + length * start.coerceIn(0f, 1f), y,
            inset + length * (start + .25f).coerceIn(0f, 1f), y, paint)
    }
}
