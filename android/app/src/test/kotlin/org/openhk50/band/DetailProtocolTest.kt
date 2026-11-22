package org.openhk50.band

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openhk50.data.SleepStage
import java.time.LocalDate

class DetailProtocolTest {
    @Test
    fun decoderWaitsForFragmentsAndSkipsCorruptedPackets() {
        val body = byteArrayOf(2, 7, 0)
        val frame = DetailProtocol.packet(DetailProtocol.SLEEP, body)
        val damaged = frame.copyOf().also { it[it.lastIndex] = 1 }
        val decoder = DetailProtocol.Decoder()

        assertTrue(decoder.add(byteArrayOf(11) + frame.copyOfRange(0, 4)).isEmpty())
        val frames = decoder.add(frame.copyOfRange(4, frame.size) + damaged + frame)
        assertEquals(2, frames.size)
        for (received in frames) {
            assertEquals(DetailProtocol.SLEEP, received.type)
            assertArrayEquals(body, received.body)
        }
    }

    @Test
    fun loginCarriesUtf16BomAndKnownCrc() {
        assertArrayEquals(
            byteArrayOf(0xbc.toByte(), 0x30, 0, 0, 0xff.toByte(), 0xff.toByte()),
            DetailProtocol.packet(DetailProtocol.INITIALIZE)
        )
        assertArrayEquals(
            byteArrayOf(1, 1, 0, -1, -2, 65, 0),
            DetailProtocol.Decoder().add(DetailProtocol.login("A")).single().body
        )
    }

    @Test
    fun sleepUsesRecordDurationAcrossMidnightAndRejectsTruncation() {
        val today = LocalDate.of(2026, 11, 22)
        val body = byteArrayOf(2, 0, 0, 0x6e, 0x05, 30, 0, 2, 60, 5, 20,
            1, 0, 0, 0, 45, 0, 3, 30, 4, 15)
        val nights = DetailProtocol.sleep(body, today)!!

        assertEquals(1390, nights[0].startMinute)
        assertEquals(60, nights[0].asleepMinutes)
        assertEquals(today.minusDays(1), nights[1].day)
        assertEquals(SleepStage.REM, nights[1].segments.last().stage)
        assertNull(DetailProtocol.sleep(body.copyOf(body.size - 1), today))
        assertNull(DetailProtocol.sleep(byteArrayOf(0, 1, 0), today))
    }

    @Test
    fun oxygenValidatesDayAndKeepsOnlyHourlyMaxima() {
        val body = ByteArray(49)
        body[0] = -2
        body[13] = 94
        body[14] = 98
        body[48] = 101
        assertArrayEquals(intArrayOf((360 shl 16) or 98), DetailProtocol.oxygen(body, 3)[2])
        assertTrue(DetailProtocol.oxygen(body, 2).isEmpty())
    }

    @Test
    fun oxygenAcceptsBundledHk50RecordsAndRejectsPartialRecords() {
        val yesterday = ByteArray(49).also { it[0] = 1; it[2] = 97 }
        val today = ByteArray(49).also { it[4] = 99 }
        val records = DetailProtocol.oxygen(yesterday + today, 7)
        assertEquals(setOf(0, 1), records.keys)
        assertArrayEquals(intArrayOf(97), records[1])
        assertArrayEquals(intArrayOf((60 shl 16) or 99), records[0])
        assertTrue(DetailProtocol.oxygen(yesterday + today.copyOf(48), 7).isEmpty())
        assertEquals(setOf(0), DetailProtocol.oxygen(yesterday + today, 1).keys)
    }
}
