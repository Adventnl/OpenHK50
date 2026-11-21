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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class Palette(
    val background: Int, val surface: Int, val ink: Int, val muted: Int, val border: Int,
    val brand: Int, val onBrand: Int, val tint: Int, val heart: Int, val steps: Int, val warning: Int,
    val night: Boolean
) {
    companion object {
        fun of(night: Boolean) = if (night) Palette(
            color("#121915"), color("#1A241E"), color("#EDF4EB"), color("#A8B8AB"), color("#34453A"),
            color("#B9E6C7"), color("#142A1D"), color("#263E30"), color("#A6E5B7"), color("#F0BA89"),
            color("#F4AD9E"), true
        ) else Palette(
            color("#F2F5EE"), color("#FCFDF8"), color("#18261E"), color("#4E6254"), color("#D7E2D7"),
            color("#285239"), Color.WHITE, color("#DCECDC"), color("#317044"), color("#9E5528"),
            color("#973C2E"), false
        )

        private fun color(hex: String) = Color.parseColor(hex)
    }
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
    PULSE, CALENDAR, BAND, BACKUP, WALK, BATTERY, LEFT, RIGHT, BLUETOOTH, CHECK, CLOCK
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
            Glyph.BLUETOOTH -> path(6f, 7f, 18f, 17f, 12f, 22f, 12f, 2f, 18f, 7f, 6f, 17f)
            Glyph.CHECK -> path(5f, 12f, 10f, 17f, 19f, 7f)
            Glyph.CLOCK -> {
                canvas.drawCircle(12f, 12f, 8.5f, paint)
                path(12f, 7f, 12f, 12f, 16f, 14f)
            }
        }
        canvas.restore()
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
        val name = if (metric == Metric.HEART) "Heart rate in beats per minute" else "Steps per 15 minutes"
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

        if (metric == Metric.HEART) {
            paint.color = palette.heart
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 1.75f * density
            path.reset()
            var previous: Reading? = null
            for (reading in readings) {
                val last = previous
                if (last == null || reading.time - last.time > 10 * 60_000) path.moveTo(x(reading.time), y(reading.value))
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
