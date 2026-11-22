package org.openhk50.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class Metric(val intervalValues: IntRange, val liveType: Int? = null) {
    HEART(1..254, 1),
    STEPS(0..65534),
    OXYGEN(1..100, 3),
    STRESS(1..100, 8),
    HRV(1..254),
    CALORIES(0..65534),
    DISTANCE(0..65534);

    val backedUp get() = this == HEART || this == STEPS
    val isTotal get() = this == STEPS || this == CALORIES || this == DISTANCE
}
enum class Origin { HISTORY, LIVE }

data class Reading(
    val band: String,
    val metric: Metric,
    val origin: Origin,
    val slot: String,
    val time: Long,
    val value: Int,
    val revisedAt: Long,
    val revision: String
) {
    init {
        require(BAND.matches(band) && REVISION.matches(revision))
        require(time in 1 until MAX_TIME && revisedAt in time until MAX_TIME)
        require(when (origin) {
            Origin.HISTORY -> SLOT.matches(slot)
            Origin.LIVE -> slot == revision && metric.liveType != null && time == revisedAt
        })
        require(value in metric.intervalValues)
    }

    companion object {
        private const val MAX_TIME = 4_102_444_800_000L
        private val BAND = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
        private val REVISION = Regex("[0-9a-f]{32}")
        private val SLOT = Regex("2[0-9]{7}T[0-9]{4}")
        private val SLOT_TIME = DateTimeFormatter.ofPattern("'T'HHmm", Locale.ROOT)

        fun history(band: String, metric: Metric, date: LocalDate, minute: Int, zone: ZoneId,
                    value: Int, observedAt: Long): Reading? {
            if (date.year !in 2000..2099) return null
            val wall = date.atStartOfDay().plusMinutes(minute.toLong())
            // The band cannot disambiguate the two occurrences of a repeated wall-clock slot.
            val offset = zone.rules.getValidOffsets(wall).firstOrNull() ?: return null
            val time = wall.toInstant(offset).toEpochMilli()
            if (time > observedAt) return null
            val slot = date.format(DateTimeFormatter.BASIC_ISO_DATE) + wall.format(SLOT_TIME)
            return Reading(band, metric, Origin.HISTORY, slot, time, value, observedAt, revision())
        }

        fun live(band: String, metric: Metric, value: Int, observedAt: Instant): Reading {
            val id = revision()
            val time = observedAt.toEpochMilli()
            return Reading(band, metric, Origin.LIVE, id, time, value, time, id)
        }

        private fun revision() = UUID.randomUUID().toString().replace("-", "")
    }
}
