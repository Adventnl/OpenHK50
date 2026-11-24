package org.openhk50.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DailyEnergyTest {
    private val date = LocalDate.parse("2026-11-23")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun todayAccruesRestingEnergySinceLocalMidnight() {
        val estimate = DailyEnergy.estimate(date, zone, Instant.parse("2026-11-23T04:00:00Z"), 1_550.0, 310)
        assertEquals(DailyEnergy(775, 310), estimate)
        assertEquals(1_085L, estimate.total)
    }

    @Test
    fun pastDayCapsRestingEnergyAtNextLocalMidnight() {
        val estimate = DailyEnergy.estimate(date, zone, Instant.parse("2026-11-24T04:00:00Z"), 1_550.0, 310)
        assertEquals(DailyEnergy(1_550, 310), estimate)
        assertEquals(1_860L, estimate.total)
    }

    @Test
    fun missingInputLeavesTotalUnavailableButRecordedZeroCounts() {
        val now = Instant.parse("2026-11-24T04:00:00Z")
        val noBand = DailyEnergy.estimate(date, zone, now, 1_550.0, null)
        assertEquals(1_550L, noBand.resting)
        assertNull(noBand.total)
        val noBasal = DailyEnergy.estimate(date, zone, now, null, 310)
        assertEquals(310L, noBasal.band)
        assertNull(noBasal.total)
        assertEquals(1_550L, DailyEnergy.estimate(date, zone, now, 1_550.0, 0).total)
    }

    @Test
    fun shortAndLongLocalDaysUseActualElapsedHours() {
        val eastern = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-11-24T04:00:00Z")
        val spring = DailyEnergy.estimate(LocalDate.parse("2026-03-08"), eastern, now, 2_400.0, 0)
        val autumn = DailyEnergy.estimate(LocalDate.parse("2026-11-01"), eastern, now, 2_400.0, 0)
        assertEquals(2_300L, spring.total)
        assertEquals(2_500L, autumn.total)
    }
}
