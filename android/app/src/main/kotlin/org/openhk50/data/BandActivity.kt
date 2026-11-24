package org.openhk50.data

import kotlin.math.roundToLong

internal data class BandActivity(val steps: Long, val milliKcal: Long, val meters: Long) {
    companion object {
        fun fromHk50Summary(frame: ByteArray): BandActivity? {
            if (frame.size != 16 || frame[0].toInt() != 0x48) return null
            fun unsigned24(index: Int) = (0..2).fold(0L) { value, offset ->
                (value shl 8) or (frame[index + offset].toLong() and 255)
            }
            val summary = BandActivity(unsigned24(1), unsigned24(7), unsigned24(10))
            return summary.takeIf {
                it.steps < 0xffffff && it.milliKcal < 0xffffff && it.meters < 0xffffff
            }
        }
    }
}

internal fun Metric.fromActivityStorage(value: Long, stepFactor: Double): Long = when (this) {
    Metric.CALORIES -> (value + 50) / 100
    Metric.STEPS -> (value * stepFactor).roundToLong()
    else -> value
}

internal fun stepFactor(counted: Int?, measured: Int?): Double? =
    if (counted != null && measured != null && counted in 50..10_000 && measured in 1..50_000) {
        (counted.toDouble() / measured).takeIf { it in .2..2.0 }
    } else null
