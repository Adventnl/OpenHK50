package org.openhk50.data

internal enum class WorkoutType(val title: String) {
    WALK("Walk"), RUN("Run"), CYCLE("Cycle"), STRENGTH("Strength"), OTHER("Other")
}

internal data class Workout(val start: Long, val type: WorkoutType, val end: Long?, val averageHeartRate: Int?) {
    fun seconds(now: Long) = ((end ?: now) - start).coerceAtLeast(0) / 1000
}

internal data class WorkoutLog(val active: Workout?, val recent: List<Workout>, val hasMore: Boolean)
