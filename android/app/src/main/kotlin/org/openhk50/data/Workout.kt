package org.openhk50.data

internal enum class WorkoutType(val title: String) {
    WALK("Walk"), RUN("Run"), CYCLE("Cycle"), HIKE("Hike"), HIIT("HIIT"),
    STRENGTH("Strength"), YOGA("Yoga"), OTHER("Other")
}

internal data class Workout(
    val start: Long, val type: WorkoutType, val end: Long?, val averageHeartRate: Int?,
    val pausedAt: Long?, val pausedMillis: Long
) {
    fun seconds(now: Long) = ((end ?: pausedAt ?: now) - start - pausedMillis).coerceAtLeast(0) / 1000
}

internal data class WorkoutLog(val active: Workout?, val recent: List<Workout>, val hasMore: Boolean)
