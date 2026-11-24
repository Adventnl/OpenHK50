package org.openhk50.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openhk50.data.Metric

class LiveMeasurementTest {
    @Test fun hk50UsesBeatIntervalWhenDirectBpmIsEmpty() {
        val frame = byteArrayOf(0x69, 1, 0, 0, 0, 0, 0x20, 0x03)
        assertEquals(LiveMeasurement(75, 800), LiveMeasurement.decode(Metric.HEART, frame, hk50 = true))
        assertNull(LiveMeasurement.decode(Metric.HEART, frame, hk50 = false))
        frame[3] = 81
        assertEquals(LiveMeasurement(81), LiveMeasurement.decode(Metric.HEART, frame, hk50 = true))
    }

    @Test fun warmupValuesNeverBecomeABpm() {
        val frame = byteArrayOf(0x69, 1, 0, 0, 0, 0, 3, 0)
        assertNull(LiveMeasurement.decode(Metric.HEART, frame, hk50 = true))
    }
}
