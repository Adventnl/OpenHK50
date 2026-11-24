package org.openhk50.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import kotlin.math.roundToInt

internal val arrive = PathInterpolator(.16f, 1f, .30f, 1f)

@SuppressLint("ViewConstructor")
internal class GlassNavigation(
    context: Context, private val palette: Palette, private val source: View, radius: Int
) : FrameLayout(context) {
    private val corner = context.dp(radius).toFloat()
    private val tint = context.glassSurface(palette, radius, floating = true)
    private val clip = Path()
    private val bounds = RectF()
    private val lens = RectF()
    private val from = RectF()
    private val to = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdrop = if (Build.VERSION.SDK_INT >= 31) Backdrop(context, corner) else null
    private var selected: View? = null
    private var motion: ValueAnimator? = null
    private var accent = palette.brand
    private var glow = 0f
    private var slide = false

    init {
        setWillNotDraw(false)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, width, height, corner)
            }
        }
        elevation = context.dp(6).toFloat()
    }

    fun select(cell: View, color: Int, animate: Boolean) {
        if (selected === cell) return
        selected = cell
        accent = color
        slide = animate && !lens.isEmpty && ValueAnimator.areAnimatorsEnabled()
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        tint.setBounds(0, 0, w, h)
        clip.reset()
        clip.addRoundRect(bounds, corner, corner, Path.Direction.CW)
        if (w > 0 && h > 0 && Build.VERSION.SDK_INT >= 31) backdrop?.resize(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val cell = selected ?: return
        val parent = cell.parent as View
        to.set((parent.left + cell.left).toFloat(), (parent.top + cell.top).toFloat(),
            (parent.left + cell.right).toFloat(), (parent.top + cell.bottom).toFloat())
        to.inset(context.dp(2).toFloat(), 0f)
        if (slide) {
            slide = false
            motion?.cancel()
            from.set(lens)
            motion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                interpolator = arrive
                addUpdateListener {
                    val t = it.animatedFraction
                    val stretch = 1f - kotlin.math.abs(2f * t - 1f)
                    val x = (to.centerX() - from.centerX()) * .14f * stretch
                    val y = (to.centerY() - from.centerY()) * .14f * stretch
                    lens.set(
                        mix(from.left, to.left, t) + minOf(0f, x),
                        mix(from.top, to.top, t) + minOf(0f, y),
                        mix(from.right, to.right, t) + maxOf(0f, x),
                        mix(from.bottom, to.bottom, t) + maxOf(0f, y)
                    )
                    glow = stretch
                    invalidate()
                }
                start()
            }
        } else if (motion?.isRunning != true || changed) {
            motion?.cancel()
            lens.set(to)
            glow = 0f
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.withClip(clip) {
            if (Build.VERSION.SDK_INT >= 31 && isHardwareAccelerated && source.isLaidOut) {
                backdrop?.draw(this, source, source.x - x, source.y - y, palette.background)
            }
            tint.draw(this)
            if (!lens.isEmpty) drawLens(this)
        }
    }

    private fun drawLens(canvas: Canvas) {
        val round = lens.height() / 2f
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(lens.left, lens.top, lens.right, lens.bottom, intArrayOf(
            ColorUtils.setAlphaComponent(accent, if (palette.night) 72 else 48),
            ColorUtils.setAlphaComponent(accent, if (palette.night) 31 else 22),
            ColorUtils.setAlphaComponent(accent, if (palette.night) 48 else 36)
        ), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(lens, round, round, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1).toFloat()
        paint.shader = LinearGradient(lens.left, lens.top, lens.right, lens.bottom, intArrayOf(
            ColorUtils.setAlphaComponent(android.graphics.Color.WHITE, (105 + 95 * glow).roundToInt()),
            ColorUtils.setAlphaComponent(accent, 55),
            ColorUtils.setAlphaComponent(accent, 130)
        ), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(lens, round, round, paint)
        paint.shader = null
    }

    override fun onDetachedFromWindow() {
        motion?.cancel()
        motion = null
        super.onDetachedFromWindow()
    }

    private fun mix(start: Float, end: Float, progress: Float) = start + (end - start) * progress

    @RequiresApi(31)
    private class Backdrop(context: Context, private val corner: Float) {
        private val node = RenderNode("Navigation backdrop")
        private val spread = context.dp(4).toFloat()

        fun resize(width: Int, height: Int) {
            node.setPosition(0, 0, width, height)
            val effect = if (Build.VERSION.SDK_INT >= 33) {
                val glass = RuntimeShader(REFRACTION)
                glass.setFloatUniform("size", width.toFloat(), height.toFloat())
                glass.setFloatUniform("radius", corner)
                glass.setFloatUniform("spread", spread)
                RenderEffect.createRuntimeShaderEffect(glass, "scene")
            } else RenderEffect.createBlurEffect(spread, spread, Shader.TileMode.CLAMP)
            node.setRenderEffect(effect)
        }

        fun draw(canvas: Canvas, source: View, x: Float, y: Float, background: Int) {
            val recording = node.beginRecording()
            try {
                recording.drawColor(background)
                recording.translate(x, y)
                source.draw(recording)
            } finally {
                node.endRecording()
            }
            canvas.drawRenderNode(node)
        }
    }

    companion object {
        private const val REFRACTION = """
            uniform shader scene;
            uniform float2 size;
            uniform float radius;
            uniform float spread;

            half4 main(float2 p) {
                float r = min(radius, min(size.x, size.y) * .5);
                float2 c = p - size * .5;
                float2 edge = max(abs(c) - (size * .5 - r), 0.0);
                float rim = smoothstep(r - spread * 5.0, r, length(edge));
                float2 q = p - normalize(c + float2(.001)) * spread * 2.5 * rim;
                return scene.eval(q) * .36 +
                    (scene.eval(q + float2(spread, 0.0)) + scene.eval(q - float2(spread, 0.0)) +
                     scene.eval(q + float2(0.0, spread)) + scene.eval(q - float2(0.0, spread))) * .16;
            }
        """
    }
}
