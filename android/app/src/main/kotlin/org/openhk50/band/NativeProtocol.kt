package org.openhk50.band

import java.time.LocalDate
import java.time.ZoneOffset

internal object NativeProtocol {
    init { System.loadLibrary("openh_k50_protocol") }

    const val BATTERY = 3
    const val HEART_HISTORY = 21
    const val HEART_LOG = 22
    const val STEPS = 67
    const val HEART_LIVE = 105
    const val HEART_STOP = 106

    private external fun encode(command: Int, payload: ByteArray): ByteArray
    external fun header(frame: ByteArray): Int
    private external fun history(frames: ByteArray, date: Int): IntArray

    fun packet(command: Int, vararg payload: Int) =
        encode(command, ByteArray(payload.size) { payload[it].toByte() })

    fun heartRequest(day: LocalDate): ByteArray {
        // Local midnight is encoded as if it were UTC.
        val midnight = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC).toInt()
        return packet(HEART_HISTORY, midnight, midnight ushr 8, midnight ushr 16, midnight ushr 24)
    }

    fun stepRequest(offset: Int) = packet(STEPS, offset, 0x0f, 0, 0x5f, 1)

    fun intervals(frames: List<ByteArray>, day: LocalDate?): IntArray {
        if (frames.isEmpty()) return IntArray(0)
        val bytes = ByteArray(frames.size * 16)
        frames.forEachIndexed { index, frame -> frame.copyInto(bytes, index * 16) }
        return history(bytes, day?.let { it.year * 10_000 + it.monthValue * 100 + it.dayOfMonth } ?: 0)
    }
}
