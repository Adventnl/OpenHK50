package org.openhk50.band

import org.openhk50.data.Metric
import kotlin.math.roundToInt

internal data class LiveMeasurement(val value: Int, val beatIntervalMs: Int? = null) {
    companion object {
        fun decode(metric: Metric, frame: ByteArray, hk50: Boolean): LiveMeasurement? {
            val direct = frame[3].toInt() and 255
            if (metric != Metric.HEART) return direct.takeIf { it in metric.intervalValues }?.let { LiveMeasurement(it) }
            if (direct in 30..240) return LiveMeasurement(direct)
            if (!hk50 || direct != 0) return null
            // HK50 can leave BPM empty while sending the latest inter-beat interval in milliseconds.
            val interval = (frame[6].toInt() and 255) or ((frame[7].toInt() and 255) shl 8)
            return if (interval in 250..2_000) LiveMeasurement((60_000.0 / interval).roundToInt(), interval) else null
        }
    }
}
