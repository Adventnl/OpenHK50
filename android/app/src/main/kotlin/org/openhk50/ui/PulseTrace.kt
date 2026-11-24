package org.openhk50.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils

@SuppressLint("ViewConstructor")
internal class PulseTrace(context: Context, private val accent: Int) : View(context) {
    private val trace = Path()
    private val transform = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var beam: LinearGradient? = null
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val mid = h / 2f
        trace.reset()
        trace.moveTo(0f, mid)
        trace.lineTo(w * .33f, mid)
        trace.lineTo(w * .39f, h * .34f)
        trace.lineTo(w * .43f, mid)
        trace.lineTo(w * .47f, mid)
        trace.lineTo(w * .51f, h * .12f)
        trace.lineTo(w * .56f, h * .88f)
        trace.lineTo(w * .61f, mid)
        trace.lineTo(w.toFloat(), mid)
        beam = if (w > 0) LinearGradient(0f, 0f, w * .48f, 0f, intArrayOf(
            ColorUtils.setAlphaComponent(accent, 0), accent,
            ColorUtils.setAlphaComponent(accent, 0)
        ), floatArrayOf(0f, .72f, 1f), Shader.TileMode.CLAMP) else null
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = ColorUtils.setAlphaComponent(accent, if (measuring && animator == null) 195 else 65)
        canvas.drawPath(trace, paint)
        if (animator != null) {
            transform.setTranslate(width * (progress * 1.5f - .5f), 0f)
            beam?.setLocalMatrix(transform)
            paint.shader = beam
            paint.alpha = 255
            canvas.drawPath(trace, paint)
            paint.shader = null
        }
    }
}
