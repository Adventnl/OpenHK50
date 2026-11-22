package org.openhk50.band

import java.time.LocalDate
import java.time.ZoneOffset

internal object NativeProtocol {
    init { System.loadLibrary("openh_k50_protocol") }

    const val BATTERY = 3
    const val HEART_HISTORY = 21
    const val HEART_LOG = 22
    const val STRESS_HISTORY = 55
    const val HRV_HISTORY = 57
    const val STEPS = 67
    const val LIVE = 105
    const val LIVE_STOP = 106
    const val CAMERA = 2
    const val FIND_DEVICE = 80
    const val PHONE_ALERT = 114

    data class ActivityInterval(val minute: Int, val steps: Int, val calories: Int, val distance: Int)

    private external fun encode(command: Int, payload: ByteArray): ByteArray
    external fun header(frame: ByteArray): Int
    private external fun history(frames: ByteArray): IntArray
    private external fun activityHistory(frames: ByteArray, date: Int): IntArray

    fun packet(command: Int, vararg payload: Int) =
        encode(command, ByteArray(payload.size) { payload[it].toByte() })

    fun heartRequest(day: LocalDate): ByteArray {
        // Local midnight is encoded as if it were UTC.
        val midnight = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC).toInt()
        return packet(HEART_HISTORY, midnight, midnight ushr 8, midnight ushr 16, midnight ushr 24)
    }

    fun stepRequest(offset: Int) = packet(STEPS, offset, 0x0f, 0, 0x5f, 1)

    fun slotIntervals(frames: List<ByteArray>): IntArray {
        val interval = frames.firstOrNull { it[1].toInt() == 0 }?.get(3)?.toInt()?.and(255)
            ?.takeIf { it > 0 } ?: 30
        val values = sortedMapOf<Int, Int>()
        for (frame in frames) {
            val index = frame[1].toInt() and 255
            if (index == 255) return IntArray(0)
            if (index == 0) continue
            for (i in 2..14) {
                val minute = ((index - 1) * 13 + i - 2) * interval
                val value = frame[i].toInt() and 255
                if (minute < 1440 && value != 0) values[minute] = value
            }
        }
        return values.map { (minute, value) -> (minute shl 16) or value }.toIntArray()
    }

    fun heartIntervals(frames: List<ByteArray>): IntArray = history(flatten(frames))

    fun activityIntervals(frames: List<ByteArray>, day: LocalDate): List<ActivityInterval> {
        val date = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth
        val values = activityHistory(flatten(frames), date)
        return (0 until values.size step 4).map { i ->
            ActivityInterval(values[i], values[i + 1], values[i + 2], values[i + 3])
        }
    }

    private fun flatten(frames: List<ByteArray>) = ByteArray(frames.size * 16).also { bytes ->
        frames.forEachIndexed { index, frame -> frame.copyInto(bytes, index * 16) }
    }
}
