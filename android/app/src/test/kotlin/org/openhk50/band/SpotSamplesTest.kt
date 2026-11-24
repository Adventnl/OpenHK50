package org.openhk50.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openhk50.data.Metric

class SpotSamplesTest {
    @Test fun heartRateCompletesAfterSixValidReadings() {
        val samples = SpotSamples(Metric.HEART)
        repeat(42) { assertFalse(samples.add(0)) }
        assertFalse(samples.complete)
        assertNull(samples.median())

        listOf(80, 74, 72, 76, 75).forEach { assertTrue(samples.add(it)) }
        assertFalse(samples.complete)
        assertEquals(75, samples.median())

        assertTrue(samples.add(82))
        assertTrue(samples.complete)
        assertFalse(samples.add(200))
        assertEquals(75, samples.median())
    }
}
