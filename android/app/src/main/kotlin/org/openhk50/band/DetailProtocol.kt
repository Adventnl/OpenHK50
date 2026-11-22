package org.openhk50.band

import org.openhk50.data.SleepNight
import org.openhk50.data.SleepSegment
import org.openhk50.data.SleepStage
import java.time.LocalDate

internal object DetailProtocol {
    const val LOGIN = 0x4a
    const val INITIALIZE = 0x30
    const val SLEEP = 0x27
    const val OXYGEN = 0x2a
    private const val MAGIC = 0xbc
    private const val HEADER = 6
    private const val MAX_BODY = 8192

    data class Frame(val type: Int, val body: ByteArray)

    fun login(username: String) = packet(LOGIN, byteArrayOf(1, 1, 0, -1, -2) +
        username.toByteArray(Charsets.UTF_16LE))

    fun packet(type: Int, body: ByteArray = byteArrayOf()): ByteArray {
        val crc = crc(body)
        return byteArrayOf(MAGIC.toByte(), type.toByte(), body.size.toByte(),
            (body.size ushr 8).toByte(), crc.toByte(), (crc ushr 8).toByte()) + body
    }

    private fun crc(bytes: ByteArray): Int {
        var value = 0xffff
        for (byte in bytes) {
            value = value xor (byte.toInt() and 255)
            repeat(8) {
                value = (value ushr 1) xor (if (value and 1 == 1) 0xa001 else 0)
            }
        }
        return value
    }

    class Decoder {
        private var pending = byteArrayOf()

        fun add(bytes: ByteArray): List<Frame> {
            pending += bytes
            var cursor = 0
            val frames = mutableListOf<Frame>()
            while (cursor < pending.size) {
                if (pending.u8(cursor) != MAGIC) {
                    cursor++
                    continue
                }
                if (pending.size - cursor < HEADER) break
                val length = pending.u16(cursor + 2)
                if (length > MAX_BODY) {
                    cursor++
                    continue
                }
                if (pending.size - cursor < HEADER + length) break
                val body = pending.copyOfRange(cursor + HEADER, cursor + HEADER + length)
                if (crc(body) == pending.u16(cursor + 4)) {
                    frames += Frame(pending.u8(cursor + 1), body)
                }
                cursor += HEADER + length
            }
            pending = pending.copyOfRange(cursor, pending.size)
            return frames
        }
    }

    fun sleep(body: ByteArray, today: LocalDate): List<SleepNight>? {
        if (body.size < 9) return null
        val count = body.u8(0)
        var cursor = 1
        val nights = mutableListOf<SleepNight>()
        repeat(count) {
            if (body.size - cursor < 6) return null
            val offset = body.u8(cursor)
            val start = body.u16(cursor + 2)
            val end = body.u16(cursor + 4)
            if (start >= 1440 || end >= 1440) return null
            cursor += 6
            val duration = Math.floorMod(end - start, 1440)
            var elapsed = 0
            val segments = mutableListOf<SleepSegment>()
            while (elapsed < duration) {
                if (body.size - cursor < 2) return null
                val stage = SleepStage.entries.getOrNull(body.u8(cursor) - 2) ?: return null
                val minutes = body.u8(cursor + 1)
                if (minutes > 0) segments += SleepSegment(stage, minutes)
                elapsed += minutes
                cursor += 2
            }
            if (elapsed != duration) return null
            if (segments.isNotEmpty()) {
                nights += SleepNight(today.minusDays(offset.toLong()), start, segments)
            }
        }
        return nights
    }

    fun oxygen(body: ByteArray, days: Int): Map<Int, IntArray> {
        val recordSize = 49
        if (body.isEmpty() || body.size % recordSize != 0) return emptyMap()
        return buildMap {
            for (start in body.indices step recordSize) {
                // HK50 replies use positive day offsets; H59 replies can echo the signed request.
                val day = kotlin.math.abs(body[start].toInt())
                if (day !in 0 until days) continue
                put(day, (0..23).mapNotNull { hour ->
                    val maximum = body.u8(start + 2 + hour * 2)
                    maximum.takeIf { it in 1..100 }?.let { (hour * 60 shl 16) or it }
                }.toIntArray())
            }
        }
    }

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 255
    private fun ByteArray.u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
}
