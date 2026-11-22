package org.openhk50.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.View
import org.openhk50.data.Metric
import org.openhk50.data.Reading
import org.openhk50.data.SleepSegment
import org.openhk50.data.SleepStage
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class Palette(
    val background: Int, val surface: Int, val ink: Int, val muted: Int, val border: Int,
    val brand: Int, val onBrand: Int, val tint: Int, val heart: Int, val steps: Int,
    val oxygen: Int, val stress: Int, val hrv: Int, val warning: Int,
    val night: Boolean
) {
    companion object {
        fun of(night: Boolean) = if (night) Palette(
            color("#15151D"), color("#20212C"), color("#F4F3FC"), color("#B2B2C6"), color("#363849"),
            color("#BABDFF"), color("#1D2050"), color("#2A2D4D"), color("#FF92AB"), color("#FFBE83"),
            color("#8ADCED"), color("#E2AAF4"), color("#A4DABB"), color("#FFAA9E"), true
        ) else Palette(
            color("#F3F3F9"), Color.WHITE, color("#181923"), color("#595D70"), color("#E0E1EC"),
            color("#363CA2"), Color.WHITE, color("#E8E9FF"), color("#A92450"), color("#925016"),
            color("#146A7C"), color("#802B88"), color("#28684C"), color("#9D363A"), false
        )

        private fun color(hex: String) = Color.parseColor(hex)
    }
}

internal fun Palette.accent(metric: Metric) = when (metric) {
    Metric.HEART -> heart
    Metric.STEPS -> steps
    Metric.OXYGEN -> oxygen
    Metric.STRESS -> stress
    Metric.HRV -> hrv
}

internal val Metric.title get() = when (this) {
    Metric.HEART -> "Heart rate"
    Metric.STEPS -> "Steps"
    Metric.OXYGEN -> "Blood oxygen"
    Metric.STRESS -> "Stress"
    Metric.HRV -> "Heart rate variability"
}

internal val Metric.unit get() = when (this) {
    Metric.HEART -> "bpm"
    Metric.STEPS -> "steps"
    Metric.OXYGEN -> "%"
    Metric.STRESS -> ""
    Metric.HRV -> "ms"
}

internal val Metric.icon get() = when (this) {
    Metric.HEART -> Glyph.PULSE
    Metric.STEPS -> Glyph.WALK
    Metric.OXYGEN -> Glyph.DROP
    Metric.STRESS -> Glyph.WAVE
    Metric.HRV -> Glyph.VARIABILITY
}

internal val SleepStage.title get() = when (this) {
    SleepStage.LIGHT -> "Light"
    SleepStage.DEEP -> "Deep"
    SleepStage.REM -> "REM"
    SleepStage.AWAKE -> "Awake"
}

internal fun Palette.sleepColor(stage: SleepStage) = when (stage) {
    SleepStage.LIGHT -> Color.parseColor(if (night) "#8088E4" else "#727BD1")
    SleepStage.DEEP -> Color.parseColor(if (night) "#B5A5FF" else "#493399")
    SleepStage.REM -> Color.parseColor(if (night) "#75D7DC" else "#24929A")
    SleepStage.AWAKE -> Color.parseColor(if (night) "#FFB192" else "#C4684D")
}

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
    DROP, WAVE, VARIABILITY, SCAN, MOON
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

internal class SeriesChart(
    context: Context, private val palette: Palette, private val metric: Metric,
    private val date: LocalDate, private val zone: ZoneId, private val readings: List<Reading>
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val labels = RectF()
    private val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val low = if (metric == Metric.STEPS) 0 else (floor((readings.minOf { it.value } - 5) / 10.0) * 10)
        .toInt().coerceAtLeast(0)
    private val high = if (metric == Metric.STEPS) maxOf(10, (ceil(readings.maxOf { it.value } / 10.0) * 10).toInt())
        else maxOf(low + 20, (ceil((readings.maxOf { it.value } + 5) / 10.0) * 10).toInt())

    init {
        val name = if (metric == Metric.STEPS) "Steps per 15 minutes" else "${metric.title} ${metric.unit}".trimEnd()
        contentDescription = "$name, ${readings.size} readings. Low ${readings.minOf { it.value }}, high ${readings.maxOf { it.value }}."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val left = 2f * density
        val right = width - 32f * density
        val top = 12f * density
        val bottom = height - 26f * density
        if (right <= left || bottom <= top) return
        paint.strokeWidth = density
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
        )
        paint.style = Paint.Style.FILL

        fun x(time: Long) = left + ((time - start).toDouble() / (end - start)).toFloat() * (right - left)
        fun y(value: Int) = bottom - (value - low).toFloat() / (high - low) * (bottom - top)
        for (i in 0..2) {
            val value = low + (high - low) * i / 2
            val at = y(value)
            paint.color = palette.border
            canvas.drawLine(left, at, right, at, paint)
            paint.color = palette.muted
            canvas.drawText(value.toString(), right + 5f * density, at + 4f * density, paint)
        }
        listOf(0, 6, 12, 18, 24).forEach { hour ->
            val label = "%02d".format(java.util.Locale.ROOT, if (hour == 24) 0 else hour)
            val time = if (hour == 24) end else date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            val center = (x(time) - paint.measureText(label) / 2).coerceAtLeast(left)
            canvas.drawText(label, center, height - 5f * density, paint)
        }

        if (metric != Metric.STEPS) {
            paint.color = palette.accent(metric)
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 1.75f * density
            path.reset()
            var previous: Reading? = null
            for (reading in readings) {
                val last = previous
                val gap = if (metric == Metric.HEART) 10 else 35
                if (last == null || reading.time - last.time > gap * 60_000) path.moveTo(x(reading.time), y(reading.value))
                else path.lineTo(x(reading.time), y(reading.value))
                previous = reading
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            for (reading in readings) canvas.drawCircle(x(reading.time), y(reading.value), 1.9f * density, paint)
        } else {
            paint.color = palette.steps
            val bar = (right - left) / 96 * .76f
            for (reading in readings) {
                val from = x(reading.time)
                labels.set(from, minOf(y(reading.value), bottom - density), from + bar, bottom)
                canvas.drawRoundRect(labels, density, density, paint)
            }
        }
    }
}
