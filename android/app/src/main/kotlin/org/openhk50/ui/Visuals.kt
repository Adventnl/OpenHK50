package org.openhk50.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.View
import org.openhk50.data.Metric
import org.openhk50.data.SleepSegment
import org.openhk50.data.SleepStage
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class Palette(
    val background: Int, val surface: Int, val ink: Int, val muted: Int, val border: Int,
    val tint: Int, val chart: Int, val night: Boolean
) {
    val brand get() = if (night) 0xffb8f6ce.toInt() else 0xff136044.toInt()
    val onBrand get() = if (night) 0xff10251a.toInt() else Color.WHITE
    val warning get() = if (night) 0xffffba99.toInt() else 0xff8b3d23.toInt()
    val calories get() = if (night) 0xffffa584.toInt() else 0xffa33f27.toInt()
    val workout get() = if (night) 0xffbda9ff.toInt() else 0xff5f409d.toInt()
    val steps get() = brand
    val sleep get() = if (night) 0xff91c7fc.toInt() else 0xff245c8f.toInt()

    companion object {
        fun of(night: Boolean) = if (night) Palette(
            color("#090B0A"), color("#181C1A"), color("#F5F7F5"), color("#ADB7B1"),
            color("#343C37"), color("#242D27"), color("#B8F6CE"), true
        ) else Palette(
            color("#F0F4F0"), Color.WHITE, color("#14211A"), color("#4E6056"),
            color("#D6DED8"), color("#E1EDE5"), color("#136044"), false
        )

        private fun color(hex: String) = Color.parseColor(hex)
    }
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
    SleepStage.LIGHT -> if (night) "#85B9E8" else "#4B85B9"
    SleepStage.DEEP -> if (night) "#6579D4" else "#384B9D"
    SleepStage.REM -> if (night) "#BEACF9" else "#8368BC"
    SleepStage.AWAKE -> if (night) "#D2DAD4" else "#CAD3CC"
})

internal fun Context.dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).roundToInt()

internal fun Context.shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radius).toFloat()
    if (stroke != null) setStroke(dp(1), stroke)
}

internal fun Context.pressable(fill: Int, ink: Int, radius: Int, stroke: Int? = null) = RippleDrawable(
    ColorStateList.valueOf((ink and 0x00ffffff) or 0x22000000),
    shape(fill, radius, stroke), shape(Color.WHITE, radius)
)

internal enum class Glyph {
    PULSE, CALENDAR, BAND, BACKUP, WALK, BATTERY, LEFT, RIGHT, DOWN, BLUETOOTH, CHECK, CLOCK,
    DROP, WAVE, VARIABILITY, SCAN, MOON, TODAY, CAMERA, PHONE, STEPS, FLAME
}

internal class Icon(context: Context, private val glyph: Glyph, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.65f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
    description: String?, private val durationAxis: Boolean
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
                val y = bottom - (bottom - top) * (value / scale).toFloat()
                val half = (step * .25f).coerceAtMost(context.dp(15).toFloat())
                val radius = minOf(half, context.dp(5).toFloat())
                paint.color = palette.chart
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
