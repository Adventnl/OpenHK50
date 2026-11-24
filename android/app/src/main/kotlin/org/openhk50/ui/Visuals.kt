package org.openhk50.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import org.openhk50.data.Metric
import org.openhk50.data.SleepSegment
import org.openhk50.data.SleepStage
import org.openhk50.data.WorkoutType
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class Palette(
    val background: Int, val surface: Int, val ink: Int, val muted: Int, val border: Int,
    val tint: Int, val night: Boolean
) {
    val brand get() = steps
    val warning get() = legible(heart)
    val calories get() = 0xffff3b42.toInt()
    val workout get() = 0xffffdf00.toInt()
    val steps get() = 0xff3988ff.toInt()
    val sleep get() = 0xffa48aff.toInt()
    val heart get() = calories
    val oxygen get() = 0xff00c9ff.toInt()
    val stress get() = 0xffffa225.toInt()

    val onAccent get() = Color.BLACK

    fun legible(accent: Int): Int {
        if (night || accent == ink || accent == muted) return accent
        return when (accent) {
            calories -> 0xffb91b2a.toInt()
            workout -> 0xff695400.toInt()
            steps -> 0xff0046b5.toInt()
            sleep -> 0xff5d34aa.toInt()
            oxygen -> 0xff005d78.toInt()
            stress -> 0xff814200.toInt()
            else -> accent
        }
    }

    fun container(accent: Int) = ColorUtils.blendARGB(surface, accent, if (night) .18f else .10f)
    fun metric(metric: Metric?) = when (metric) {
        Metric.HEART -> heart
        Metric.OXYGEN -> oxygen
        Metric.STRESS -> stress
        Metric.HRV -> workout
        Metric.CALORIES -> calories
        Metric.DISTANCE -> oxygen
        Metric.STEPS -> steps
        null -> sleep
    }

    fun glyph(glyph: Glyph) = when (glyph) {
        Glyph.PULSE, Glyph.PHONE -> heart
        Glyph.WALK, Glyph.RUN, Glyph.STRENGTH, Glyph.VARIABILITY, Glyph.BACKUP -> workout
        Glyph.CYCLE, Glyph.DROP, Glyph.BLUETOOTH -> oxygen
        Glyph.STEPS -> steps
        Glyph.HIKE, Glyph.TODAY, Glyph.SCAN, Glyph.BAND, Glyph.BATTERY -> brand
        Glyph.CLOCK, Glyph.MOON, Glyph.CALENDAR -> sleep
        Glyph.FLAME, Glyph.HIIT, Glyph.CAMERA -> calories
        Glyph.WAVE, Glyph.YOGA -> stress
        else -> brand
    }

    companion object {
        fun of(night: Boolean) = if (night) Palette(
            Color.BLACK, 0xff101216.toInt(), Color.WHITE, 0xffaeb7c7.toInt(),
            0xff2b3039.toInt(), 0xff1a202b.toInt(), true
        ) else Palette(
            0xffedf3ff.toInt(), Color.WHITE, 0xff091325.toInt(), 0xff46536b.toInt(),
            0xffd3deef.toInt(), 0xffe2ebfc.toInt(), false
        )
    }
}

internal val WorkoutType.icon get() = when (this) {
    WorkoutType.WALK -> Glyph.STEPS
    WorkoutType.RUN -> Glyph.RUN
    WorkoutType.CYCLE -> Glyph.CYCLE
    WorkoutType.HIKE -> Glyph.HIKE
    WorkoutType.HIIT -> Glyph.HIIT
    WorkoutType.STRENGTH -> Glyph.STRENGTH
    WorkoutType.YOGA -> Glyph.YOGA
    WorkoutType.OTHER -> Glyph.WALK
}

internal val Metric.title get() = when (this) {
    Metric.HEART -> "Heart rate"
    Metric.STEPS -> "Steps"
    Metric.OXYGEN -> "Blood oxygen"
    Metric.STRESS -> "Stress"
    Metric.HRV -> "Heart rate variability"
    Metric.CALORIES -> "Band calories"
    Metric.DISTANCE -> "Distance"
}

internal val Metric.unit get() = when (this) {
    Metric.HEART -> "bpm"
    Metric.STEPS -> "steps"
    Metric.OXYGEN -> "%"
    Metric.STRESS -> ""
    Metric.HRV -> "ms"
    Metric.CALORIES -> "kcal"
    Metric.DISTANCE -> "m"
}

internal val Metric.icon get() = when (this) {
    Metric.HEART -> Glyph.PULSE
    Metric.STEPS -> Glyph.STEPS
    Metric.OXYGEN -> Glyph.DROP
    Metric.STRESS -> Glyph.WAVE
    Metric.HRV -> Glyph.VARIABILITY
    Metric.CALORIES -> Glyph.FLAME
    Metric.DISTANCE -> Glyph.WALK
}

internal val SleepStage.title get() = when (this) {
    SleepStage.LIGHT -> "Light"
    SleepStage.DEEP -> "Deep"
    SleepStage.REM -> "REM"
    SleepStage.AWAKE -> "Awake"
}

internal fun Palette.sleepColor(stage: SleepStage) = Color.parseColor(when (stage) {
    SleepStage.LIGHT -> if (night) "#44A6FF" else "#1B75D5"
    SleepStage.DEEP -> if (night) "#6268FF" else "#3747C6"
    SleepStage.REM -> if (night) "#C291FF" else "#8B4FD5"
    SleepStage.AWAKE -> if (night) "#FFDF00" else "#A67A00"
})

internal fun Context.dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).roundToInt()

internal fun Context.shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
    if (stroke != null) setStroke(dp(1), stroke)
}

internal fun Context.panelSurface(palette: Palette, radius: Int, accent: Int? = null) =
    shape(accent?.let(palette::container) ?: palette.surface, radius, palette.border)

internal fun Context.accentSurface(palette: Palette, accent: Int, radius: Int = 24) =
    panelSurface(palette, radius, accent)

internal fun Context.pressable(fill: Int, ink: Int, radius: Int, stroke: Int? = null) =
    pressable(shape(fill, radius, stroke), ink, radius)

internal fun Context.pressable(fill: Drawable, ink: Int, radius: Int) = RippleDrawable(
    ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, 38)), fill, shape(Color.WHITE, radius)
)

internal enum class Glyph {
    PULSE, CALENDAR, BAND, BACKUP, WALK, BATTERY, LEFT, RIGHT, DOWN, BLUETOOTH, CHECK, CLOCK,
    DROP, WAVE, VARIABILITY, SCAN, MOON, TODAY, CAMERA, PHONE, STEPS, FLAME,
    RUN, CYCLE, HIKE, HIIT, STRENGTH, YOGA, SHARE
}

internal class Icon(context: Context, private val glyph: Glyph, private var color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    fun setColor(value: Int) {
        if (color == value) return
        color = value
        invalidate()
    }
    private val lines = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 24f, size / 24f)
        paint.color = color

        fun path(vararg xy: Float) {
            lines.reset()
            lines.moveTo(xy[0], xy[1])
            for (i in 2 until xy.size step 2) lines.lineTo(xy[i], xy[i + 1])
            canvas.drawPath(lines, paint)
        }

        fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float = 2f) =
            canvas.drawRoundRect(l, t, r, b, radius, radius, paint)

        when (glyph) {
            Glyph.SHARE -> {
                path(12f, 15f, 12f, 3f, 8f, 7f)
                path(12f, 3f, 16f, 7f)
                path(6f, 11f, 4f, 11f, 4f, 21f, 20f, 21f, 20f, 11f, 18f, 11f)
            }
            Glyph.TODAY -> {
                canvas.drawArc(3f, 3f, 21f, 21f, 120f, 300f, false, paint)
                canvas.drawArc(7f, 7f, 17f, 17f, 120f, 235f, false, paint)
            }
            Glyph.STEPS -> {
                canvas.drawOval(5f, 3f, 10f, 11f, paint)
                canvas.drawOval(14f, 11f, 19f, 19f, paint)
                path(6f, 14f, 8f, 15f)
                path(16f, 22f, 18f, 21f)
            }
            Glyph.FLAME -> {
                lines.reset()
                lines.moveTo(13f, 2.5f)
                lines.cubicTo(15f, 7f, 11f, 8f, 14f, 12f)
                lines.cubicTo(15f, 13f, 17f, 11f, 17f, 9f)
                lines.cubicTo(24f, 17f, 16f, 23f, 11f, 21f)
                lines.cubicTo(3f, 20f, 3f, 12f, 8f, 8f)
                lines.cubicTo(8f, 10f, 9f, 11f, 10f, 11f)
                lines.cubicTo(9f, 7f, 13f, 6f, 13f, 2.5f)
                canvas.drawPath(lines, paint)
            }
            Glyph.CAMERA -> {
                path(3f, 7f, 8f, 7f, 9f, 5f, 15f, 5f, 16f, 7f, 21f, 7f, 21f, 19f, 3f, 19f, 3f, 7f)
                canvas.drawCircle(12f, 13f, 3.25f, paint)
            }
            Glyph.PHONE -> path(5f, 4f, 9f, 4f, 10f, 9f, 8f, 10f, 10f, 14f, 14f, 16f, 15f, 14f,
                20f, 15f, 20f, 19f, 17f, 20f, 12f, 18f, 7f, 14f, 4f, 8f, 5f, 4f)
            Glyph.PULSE -> path(2f, 12f, 7f, 12f, 9.6f, 5f, 13.2f, 19f, 16.1f, 10f, 18f, 12f, 22f, 12f)
            Glyph.CALENDAR -> {
                rect(3f, 5f, 21f, 21f)
                path(7f, 3f, 7f, 8f)
                path(17f, 3f, 17f, 8f)
                path(3f, 10f, 21f, 10f)
                path(7f, 15f, 9f, 15f)
                path(13f, 15f, 16f, 15f)
            }
            Glyph.BAND -> {
                rect(6f, 3f, 18f, 21f, 5f)
                path(9f, 5f, 9f, 19f)
                path(14f, 11f, 15.5f, 11f)
            }
            Glyph.BACKUP -> {
                rect(4f, 4f, 20f, 10f)
                rect(4f, 14f, 20f, 20f)
                path(7.5f, 7f, 8f, 7f)
                path(7.5f, 17f, 8f, 17f)
            }
            Glyph.WALK -> {
                canvas.drawCircle(14f, 5f, 1.8f, paint)
                path(7f, 12f, 10f, 9f, 13f, 9f, 16f, 13f, 20f, 13f)
                path(13f, 9f, 11f, 15f, 15f, 17f, 17f, 22f)
                path(11f, 15f, 9f, 19f, 5f, 21f)
            }
            Glyph.RUN -> {
                canvas.drawCircle(16f, 4.5f, 1.8f, paint)
                path(7f, 10f, 11f, 7.5f, 14.5f, 9f, 18f, 12.5f, 21f, 11f)
                path(14.5f, 9f, 11f, 14f, 16f, 16f, 19f, 21f)
                path(11f, 14f, 8f, 18f, 3f, 19f)
            }
            Glyph.CYCLE -> {
                canvas.drawCircle(5.5f, 16f, 3.5f, paint)
                canvas.drawCircle(18.5f, 16f, 3.5f, paint)
                path(5.5f, 16f, 9f, 10f, 15f, 10f, 12f, 16f, 5.5f, 16f)
                path(18.5f, 16f, 15f, 6f, 18f, 6f)
                path(7f, 8f, 11f, 8f)
            }
            Glyph.HIKE -> {
                path(2f, 19f, 9f, 7f, 14f, 15f, 17.5f, 10f, 22f, 19f, 2f, 19f)
                path(7.5f, 11f, 9f, 13f, 10.5f, 11f)
            }
            Glyph.HIIT -> path(14f, 2.5f, 5f, 14f, 11f, 14f, 10f, 21.5f, 19f, 10f, 13f, 10f, 14f, 2.5f)
            Glyph.STRENGTH -> {
                rect(4f, 7f, 8f, 17f, 1.5f)
                rect(16f, 7f, 20f, 17f, 1.5f)
                path(2f, 10f, 2f, 14f)
                path(22f, 10f, 22f, 14f)
                path(8f, 12f, 16f, 12f)
            }
            Glyph.YOGA -> {
                canvas.drawCircle(12f, 5f, 2f, paint)
                path(5f, 14f, 8f, 12f, 10f, 9f, 14f, 9f, 16f, 12f, 19f, 14f)
                path(12f, 15f, 7f, 17f, 4f, 19f, 8f, 20f, 16f, 20f, 20f, 19f, 17f, 17f, 12f, 15f)
            }
            Glyph.BATTERY -> {
                rect(2f, 7f, 19f, 17f)
                path(22f, 10f, 22f, 14f)
                path(6f, 12f, 14f, 12f)
            }
            Glyph.LEFT -> path(14f, 5f, 7f, 12f, 14f, 19f)
            Glyph.RIGHT -> path(10f, 5f, 17f, 12f, 10f, 19f)
            Glyph.DOWN -> path(5f, 9f, 12f, 16f, 19f, 9f)
            Glyph.BLUETOOTH -> path(6f, 7f, 18f, 17f, 12f, 22f, 12f, 2f, 18f, 7f, 6f, 17f)
            Glyph.CHECK -> path(5f, 12f, 10f, 17f, 19f, 7f)
            Glyph.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8.5f, paint)
                path(12f, 7f, 12f, 12f, 16f, 14f)
            }
            Glyph.DROP -> {
                lines.reset()
                lines.moveTo(12f, 3f)
                lines.cubicTo(10f, 7f, 6f, 10f, 6f, 14f)
                lines.cubicTo(6f, 22f, 18f, 22f, 18f, 14f)
                lines.cubicTo(18f, 10f, 14f, 7f, 12f, 3f)
                canvas.drawPath(lines, paint)
            }
            Glyph.WAVE -> {
                lines.reset()
                lines.moveTo(3f, 12f)
                lines.cubicTo(6f, 4f, 9f, 4f, 12f, 12f)
                lines.cubicTo(15f, 20f, 18f, 20f, 21f, 12f)
                canvas.drawPath(lines, paint)
            }
            Glyph.VARIABILITY -> {
                path(6f, 11f, 6f, 18f)
                path(12f, 5f, 12f, 18f)
                path(18f, 8f, 18f, 18f)
            }
            Glyph.SCAN -> {
                path(8f, 4f, 4f, 4f, 4f, 8f)
                path(16f, 4f, 20f, 4f, 20f, 8f)
                path(20f, 16f, 20f, 20f, 16f, 20f)
                path(8f, 20f, 4f, 20f, 4f, 16f)
                canvas.drawCircle(12f, 12f, 3f, paint)
            }
            Glyph.MOON -> {
                lines.reset()
                lines.moveTo(12f, 3.5f)
                lines.cubicTo(5f, 4f, 2f, 11.5f, 6f, 17f)
                lines.cubicTo(10f, 22.5f, 19f, 21f, 21f, 14f)
                lines.cubicTo(14f, 17f, 9.5f, 11f, 12f, 3.5f)
                canvas.drawPath(lines, paint)
            }
        }
        canvas.restore()
    }
}

internal class SleepTimeline(context: Context, palette: Palette, segments: List<SleepSegment>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cuts = segments.map { it.minutes to palette.sleepColor(it.stage) }
    private val total = cuts.sumOf { it.first }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        if (total == 0) return
        var minutes = 0
        for ((duration, color) in cuts) {
            val begin = minutes.toFloat() / total * width
            minutes += duration
            paint.color = color
            canvas.drawRect(begin, 0f, minutes.toFloat() / total * width, height.toFloat(), paint)
        }
    }
}

internal data class ChartPoint(val axis: String, val label: String, val value: Long?)

internal class BarChart(
    context: Context, private val palette: Palette, private val points: List<ChartPoint>,
    description: String?, private val accent: Int, private val durationAxis: Boolean, private val reveal: Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peak = points.maxOfOrNull { it.value ?: 0 } ?: 0
    private val halfPeak = (peak / 2.0).coerceAtLeast(1.0)
    private val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(halfPeak)))
    private val halfScale = if (durationAxis) {
        listOf(15, 30, 60, 120, 180, 240, 360, 480, 720).firstOrNull { it >= halfPeak }?.toDouble() ?: numericHalfScale()
    } else numericHalfScale()
    private val scale = 2 * halfScale
    private val compactNumber = java.text.NumberFormat.getInstance().apply { maximumFractionDigits = 1 }
    private val ticks = listOf(0L, halfScale.toLong(), scale.toLong()).map(::axisNumber)

    init {
        contentDescription = description
        importantForAccessibility = if (description == null) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private var animation: ValueAnimator? = null
    private var progress = 1f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!reveal || !ValueAnimator.areAnimatorsEnabled()) return
        progress = 0f
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedFraction
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        animation = null
        progress = 1f
        super.onDetachedFromWindow()
    }

    private fun numericHalfScale() = listOf(1, 2, 5, 10).first { it * magnitude >= halfPeak } * magnitude

    private fun axisNumber(value: Long) = when {
        durationAxis && value >= 60 -> "${compactNumber.format(value / 60.0)}h"
        durationAxis && value > 0 -> "${compactNumber.format(value)}m"
        value >= 1_000_000_000 -> "${compactNumber.format(value / 1_000_000_000.0)}B"
        value >= 1_000_000 -> "${compactNumber.format(value / 1_000_000.0)}M"
        value >= 1_000 -> "${compactNumber.format(value / 1_000.0)}k"
        else -> compactNumber.format(value)
    }

    override fun onDraw(canvas: Canvas) {
        val top = context.dp(9).toFloat()
        paint.textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
        )
        val font = paint.fontMetrics
        val bottom = height - (font.descent - font.ascent) - context.dp(8)
        val plot = width - axisWidth()
        if (plot <= 0 || bottom <= top || points.isEmpty()) return
        val step = plot.toFloat() / points.size
        paint.strokeWidth = context.dp(1).toFloat()
        paint.textAlign = Paint.Align.LEFT
        for (line in 0..2) {
            val y = bottom - (bottom - top) * line / 2
            paint.color = palette.border
            canvas.drawLine(0f, y, plot.toFloat(), y, paint)
            paint.color = palette.muted
            canvas.drawText(ticks[line], (plot + context.dp(5)).toFloat(), y - font.ascent / 2, paint)
        }

        paint.textAlign = Paint.Align.CENTER
        points.forEachIndexed { index, point ->
            val x = step * (index + .5f)
            paint.color = palette.muted
            if (paint.measureText(point.axis) <= step - context.dp(2)) {
                canvas.drawText(point.axis, x, height - font.descent, paint)
            }
            point.value?.let { value ->
                val delay = index.toFloat() / points.size * .25f
                val t = arrive.getInterpolation(((progress - delay) / (1f - delay)).coerceIn(0f, 1f))
                if (t == 0f) return@let
                val y = bottom - (bottom - top) * (value / scale).toFloat() * t
                val half = (step * .25f).coerceAtMost(context.dp(15).toFloat())
                val radius = minOf(half, context.dp(5).toFloat())
                paint.color = accent
                canvas.drawRoundRect(x - half, minOf(y, bottom - context.dp(2)), x + half, bottom, radius, radius, paint)
            }
        }
    }

    fun axisWidth(): Int {
        paint.textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
        )
        return ceil(ticks.maxOf { paint.measureText(it) }).toInt() + context.dp(8)
    }
}
