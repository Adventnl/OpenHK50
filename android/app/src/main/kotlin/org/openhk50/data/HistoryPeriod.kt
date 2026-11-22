package org.openhk50.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal enum class HistoryPeriod(val title: String) {
    DAY("Day"), WEEK("Week"), YEAR("Year");

    fun dates(end: LocalDate): List<LocalDate> = when (this) {
        DAY -> listOf(end)
        WEEK -> List(7) { end.minusDays(6L - it) }
        YEAR -> List(12) { YearMonth.from(end).minusMonths(11L - it).atDay(1) }
    }

    fun next(end: LocalDate) = when (this) {
        DAY -> end.plusDays(1)
        WEEK -> end.plusDays(7)
        YEAR -> YearMonth.from(end).plusMonths(12).atEndOfMonth()
    }

    fun endDate(selected: LocalDate, today: LocalDate) =
        (if (this == YEAR) YearMonth.from(selected).atEndOfMonth() else selected).coerceAtMost(today)
}

internal data class Aggregate(val sum: Long = 0, val count: Long = 0) {
    operator fun plus(other: Aggregate) = Aggregate(sum + other.sum, count + other.count)

    fun value(total: Boolean): Long? = when {
        count == 0L -> null
        total -> sum
        else -> (sum + count / 2) / count
    }
}

internal fun List<Aggregate>.combined() = fold(Aggregate(), Aggregate::plus)

internal const val DAY_BUCKET_HOURS = 3

internal fun dayBoundaries(date: LocalDate, zone: ZoneId): List<Long> =
    (0..24 step DAY_BUCKET_HOURS).map { hour ->
        val wall = date.atStartOfDay().plusHours(hour.toLong())
        val transition = zone.rules.getTransition(wall)
        val instant = if (transition?.isGap == true) transition.instant else wall.atZone(zone).toInstant()
        instant.toEpochMilli()
    }
