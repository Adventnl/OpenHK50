package org.openhk50.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EcgTraceTest {
    @Test fun appleWatchVoltageAndDecimalSeparatorArePreserved() {
        val trace = EcgTrace.readCsv("""
            Name,
            Sample Rate,500 hertz
            Lead,Lead I
            Unit,µV

            -300,0
            200,0
        """.trimIndent().reader())
        assertEquals(2f, trace.durationMs, 1e-6f)
        assertEquals(-.3f, trace.points.first().millivolts, 1e-6f)
    }

    @Test fun timestampedFileUsesRelativeTime() {
        val trace = EcgTrace.readCsv("time_ms,mv\n12,0.2\n14,0.3".reader())
        assertEquals(listOf(EcgPoint(0f, .2f), EcgPoint(2f, .3f)), trace.points)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfOrderTimesAreRejected() {
        EcgTrace.readCsv("time_ms,mv\n12,0.2\n10,0.3".reader())
    }
}
