package org.openhk50.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HealthInsightsTest {
    @Test fun incompleteBaselineAndLongGapsDoNotProduceScores() {
        val night = NightVitals(480, 60, 40)
        assertNull(calculateInsights(night, List(6) { night }, emptyList()).recovery)
        val samples = List(3) { HeartSample(it * 3_600_000L, 120) }
        val data = calculateInsights(night.copy(hrv = null), List(7) { night }, samples)
        assertNull(data.recovery)
        assertNull(data.strain)
        assertEquals(0, data.heartMinutes)
    }

    @Test fun scoresUseCompleteBaselineAndObservedHeartRateCoverage() {
        val night = NightVitals(480, 60, 40)
        val samples = List(4) { HeartSample(it * 15 * 60_000L, 132) }
        val data = calculateInsights(night, List(7) { night }, samples)
        assertEquals(100, data.recovery)
        assertNotNull(data.strain)
        assertEquals(45, data.heartMinutes)
    }
}
