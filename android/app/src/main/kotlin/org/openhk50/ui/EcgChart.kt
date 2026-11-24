package org.openhk50.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import org.openhk50.data.EcgTrace
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

@SuppressLint("ViewConstructor")
internal class EcgChart(context: Context, private val palette: Palette, private val trace: EcgTrace) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(1).toFloat() }
    private val path = Path()
    var startMs = 0f
        set(value) {
            field = value
            invalidate()
        }
    val windowMs = min(trace.durationMs, 6_000f)
    val voltageRange = (ceil(trace.points.maxOf { abs(it.millivolts) } * 2) / 2).coerceAtLeast(.5f)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        paint.style = Paint.Style.STROKE
        paint.color = palette.border
        for (line in 0..8) canvas.drawLine(0f, h * line / 8, w, h * line / 8, paint)
        val end = startMs + windowMs
        var tick = ceil(startMs / 1_000f) * 1_000
        while (tick <= end) {
            val x = (tick - startMs) * w / windowMs
            canvas.drawLine(x, 0f, x, h, paint)
            tick += 1_000
        }

        val points = trace.points
        val match = points.binarySearchBy(startMs) { it.milliseconds }
        val first = if (match >= 0) match else (-match - 2).coerceAtLeast(0)
        path.rewind()
        for (index in first..points.lastIndex) {
            val point = points[index]
            val x = (point.milliseconds - startMs) * w / windowMs
            val y = h / 2 - point.millivolts * h / (voltageRange * 2)
            if (index == first) path.moveTo(x, y) else path.lineTo(x, y)
            if (point.milliseconds > end) break
        }
        paint.color = palette.legible(palette.heart)
        canvas.drawPath(path, paint)
    }
}
