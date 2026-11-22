package org.openhk50.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class HistoryPeriodTest {
    @Test fun averagesWeightStoredSamplesAndKeepZeroDistinctFromMissing() {
        val buckets = listOf(Aggregate(60, 1), Aggregate(), Aggregate(360, 4))
        assertEquals(84L, buckets.combined().value(total = false))
        assertEquals(420L, buckets.combined().value(total = true))
        assertEquals(0L, Aggregate(0, 1).value(total = true))
        assertNull(Aggregate().value(total = false))
    }

    @Test fun annualHistoryKeepsTwelveMonthBucketsAcrossCalendarYears() {
        val end = LocalDate.of(2026, 2, 17)
        val dates = HistoryPeriod.YEAR.dates(end)
        assertEquals(12, dates.size)
        assertEquals(LocalDate.of(2025, 3, 1), dates.first())
        assertEquals(LocalDate.of(2026, 2, 1), dates.last())
        assertEquals(LocalDate.of(2027, 2, 28), HistoryPeriod.YEAR.next(end))
        assertEquals(LocalDate.of(2026, 2, 28), HistoryPeriod.YEAR.endDate(end, end.plusMonths(1)))
        assertEquals(end, HistoryPeriod.YEAR.endDate(end, end))
    }

    @Test fun dailyBucketsRespectClockChangesAndSkippedDates() {
        val newYork = ZoneId.of("America/New_York")
        val spring = dayBoundaries(LocalDate.of(2026, 3, 8), newYork)
        val autumn = dayBoundaries(LocalDate.of(2026, 11, 1), newYork)
        assertEquals(9, spring.size)
        assertEquals(9, autumn.size)
        assertEquals(TimeUnit.HOURS.toMillis(23), spring.last() - spring.first())
        assertEquals(TimeUnit.HOURS.toMillis(25), autumn.last() - autumn.first())
        val skipped = dayBoundaries(LocalDate.of(2011, 12, 30), ZoneId.of("Pacific/Apia"))
        assertEquals(1, skipped.distinct().size)
    }
}
