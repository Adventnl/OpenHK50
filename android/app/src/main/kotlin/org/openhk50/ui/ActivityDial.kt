package org.openhk50.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import java.time.LocalTime

internal data class GoalProgress(val value: Long?, val target: Int, val color: Int)

@SuppressLint("ViewConstructor")
internal class ActivityDial(
    context: Context, private val palette: Palette, private val rings: List<GoalProgress>, previous: List<Float>?
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val targets = rings.map { ((it.value ?: 0L).toDouble() / it.target).coerceIn(0.0, 1.0).toFloat() }
    private val start = previous ?: List(rings.size) { 0f }
    private var fraction = 1f
    private var animator: ValueAnimator? = null
    private val stagger = previous == null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    val visibleProgress get() = targets.indices.map(::progress)

    private fun progress(index: Int): Float {
        val delay = if (stagger) index * .12f else 0f
        val time = ((fraction - delay) / (1f - delay)).coerceIn(0f, 1f)
        return start[index] + (targets[index] - start[index]) * arrive.getInterpolation(time)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (start == targets || !ValueAnimator.areAnimatorsEnabled()) return
        fraction = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (stagger) 900 else 590
            interpolator = LinearInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0) return
        canvas.withTranslation((width - size) / 2, (height - size) / 2) {
            scale(size / 144f, size / 144f)
            paint.strokeWidth = 1.1f
            paint.color = ColorUtils.blendARGB(palette.border, palette.ink, .18f)
            for (index in 0 until 32) {
                withRotation(index * 11.25f, 72f, 72f) {
                    drawLine(72f, 3f, 72f, if (index % 4 == 0) 8f else 5.5f, paint)
                }
            }
            paint.strokeCap = Paint.Cap.ROUND
            rings.forEachIndexed { index, ring ->
                val radius = 56f - index * 13f
                val start = 136f + index * 25
                val sweep = 294f - index * 18
                paint.strokeWidth = 9f
                paint.color = ColorUtils.blendARGB(palette.surface, ring.color, if (palette.night) .40f else .28f)
                drawArc(72 - radius, 72 - radius, 72 + radius, 72 + radius, start, sweep, false, paint)
                val progress = progress(index)
                if (progress > 0) {
                    paint.color = ring.color
                    drawArc(72 - radius, 72 - radius, 72 + radius, 72 + radius, start, progress * sweep, false, paint)
                }
            }
        }
    }
}

@SuppressLint("ViewConstructor")
internal class AnalogFace(context: Context, private val palette: Palette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0) return
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(size / 200f, size / 200f)
        for (mark in 0 until 60) {
            val hour = mark % 5 == 0
            paint.color = if (hour) palette.muted else palette.border
            paint.strokeWidth = if (hour) 2f else 1f
            canvas.drawLine(0f, -94f, 0f, if (hour) -85f else -90f, paint)
            canvas.rotate(6f)
        }
        val time = LocalTime.now()
        fun hand(angle: Float, length: Float, weight: Float, color: Int) {
            paint.color = color
            paint.strokeWidth = weight
            canvas.withRotation(angle) { drawLine(0f, 7f, 0f, -length, paint) }
        }
        hand(time.hour * 30f + time.minute / 2f, 49f, 5f, palette.ink)
        hand(time.minute * 6f + time.second / 10f, 70f, 3f, palette.ink)
        hand(time.second * 6f, 80f, 2f, palette.brand)
        canvas.drawCircle(0f, 0f, 4f, paint)
        canvas.restore()
        postInvalidateDelayed(1_000L - System.currentTimeMillis() % 1_000L)
    }
}
