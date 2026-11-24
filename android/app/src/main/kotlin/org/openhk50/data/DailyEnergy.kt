package org.openhk50.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

internal data class DailyEnergy(val resting: Long?, val band: Long?) {
    val total: Long? get() = if (resting != null && band != null) resting + band else null

    companion object {
        fun estimate(date: LocalDate, zone: ZoneId, now: Instant, basalKcalPerDay: Double?, band: Long?): DailyEnergy {
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val elapsed = Duration.between(start, now.coerceIn(start, end)).seconds
            val resting = basalKcalPerDay?.let { (it * elapsed / 86_400).roundToLong() }
            return DailyEnergy(resting, band)
        }
    }
}
