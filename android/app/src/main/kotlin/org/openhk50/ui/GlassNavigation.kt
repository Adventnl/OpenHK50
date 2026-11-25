package org.openhk50.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import com.example.liquidglass.LiquidGlassView
import kotlin.math.abs
import kotlin.math.roundToInt

internal val arrive = PathInterpolator(.16f, 1f, .30f, 1f)

@SuppressLint("ViewConstructor")
internal class GlassNavigation(
    context: Context, private val palette: Palette, source: View, radius: Int
) : LiquidGlassView(context) {
    private val lens = RectF()
    private val from = RectF()
    private val target = RectF()
    private val indicator = LiquidGlassView(context).apply {
        configure(source)
        cornerRadius = 999f
        bevelWidth = context.dp(6).toFloat()
        refractionHeight = context.dp(5).toFloat()
        dispersionStrength = .04f
        blurAmount = .27f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var selected: View? = null
    private var motion: ValueAnimator? = null
    private var accent = palette.brand
    private var targetAccent = accent

    init {
        configure(source)
        cornerRadius = context.dp(radius).toFloat()
        bevelWidth = context.dp(11).toFloat()
        refractionHeight = context.dp(12).toFloat()
        refractionOutward = true
        dispersionStrength = .08f
        blurAmount = .29f
        setGlassTint(if (palette.night) 0xff111b2b.toInt() else 0xffedf3ff.toInt(),
            if (palette.night) .72f else .26f)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, width, height, cornerRadius)
            }
        }
        elevation = context.dp(5).toFloat()
        addView(indicator, LayoutParams(0, 0))
    }

    private fun LiquidGlassView.configure(source: View) {
        backdropSource = source
        overLight = !palette.night
        enablePressEffect = false
        collectFrameStats = false
    }

    fun refreshBackdrop() {
        invalidate()
        indicator.invalidate()
    }

    fun select(cell: View, color: Int, animate: Boolean) {
        if (selected === cell) return
        selected = cell
        targetAccent = color
        if (!isLaidOut || cell.width == 0) return
        readTarget(cell)
        if (animate && !lens.isEmpty && ValueAnimator.areAnimatorsEnabled()) {
            animateLens()
        } else {
            snapLens()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val cell = selected ?: return
        readTarget(cell)
        if (changed || motion?.isRunning != true) snapLens() else placeLens()
    }

    private fun readTarget(cell: View) {
        val parent = cell.parent as View
        target.set((parent.left + cell.left).toFloat(), (parent.top + cell.top).toFloat(),
            (parent.left + cell.right).toFloat(), (parent.top + cell.bottom).toFloat())
        val inset = context.dp(2).toFloat()
        target.inset(inset, 0f)
    }

    private fun animateLens() {
        motion?.cancel()
        from.set(lens)
        val previousAccent = accent
        val horizontal = abs(target.centerX() - from.centerX()) >= abs(target.centerY() - from.centerY())
        val forward = if (horizontal) target.centerX() >= from.centerX() else target.centerY() >= from.centerY()

        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 470
            interpolator = LinearInterpolator()
            addUpdateListener {
                val progress = it.animatedFraction
                val front = arrive.getInterpolation((progress / .76f).coerceAtMost(1f))
                val tail = arrive.getInterpolation(((progress - .13f) / .87f).coerceIn(0f, 1f))
                val near = if (forward) tail else front
                val far = if (forward) front else tail
                val middle = (front + tail) / 2
                lens.set(
                    mix(from.left, target.left, if (horizontal) near else middle),
                    mix(from.top, target.top, if (horizontal) middle else near),
                    mix(from.right, target.right, if (horizontal) far else middle),
                    mix(from.bottom, target.bottom, if (horizontal) middle else far)
                )
                val pinch = context.dp(5) * (front - tail).coerceAtLeast(0f)
                lens.inset(if (horizontal) 0f else pinch, if (horizontal) pinch else 0f)
                accent = ColorUtils.blendARGB(previousAccent, targetAccent, middle)
                placeLens()
            }
            start()
        }
    }

    private fun snapLens() {
        motion?.cancel()
        lens.set(target)
        accent = targetAccent
        placeLens()
    }

    private fun placeLens() {
        val left = lens.left.roundToInt()
        val top = lens.top.roundToInt()
        val right = lens.right.roundToInt()
        val bottom = lens.bottom.roundToInt()
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return
        indicator.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        indicator.layout(left, top, right, bottom)
        indicator.setGlassTint(accent, if (palette.night) .56f else .36f)
        indicator.invalidate()
    }

    override fun onDetachedFromWindow() {
        motion?.cancel()
        motion = null
        super.onDetachedFromWindow()
    }

    private fun mix(start: Float, end: Float, progress: Float) = start + (end - start) * progress
}
