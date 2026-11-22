package org.openhk50.data

import java.time.LocalDate

enum class SleepStage { LIGHT, DEEP, REM, AWAKE }

data class SleepSegment(val stage: SleepStage, val minutes: Int)

data class SleepNight(val day: LocalDate, val startMinute: Int, val segments: List<SleepSegment>) {
    val asleepMinutes get() = segments.filter { it.stage != SleepStage.AWAKE }.sumOf { it.minutes }
}
