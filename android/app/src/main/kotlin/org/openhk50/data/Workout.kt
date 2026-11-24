package org.openhk50.data

import kotlin.math.roundToLong

internal enum class WorkoutType(val title: String) {
    WALK("Walk"), RUN("Run"), CYCLE("Cycle"), HIKE("Hike"), HIIT("HIIT"),
    STRENGTH("Strength"), YOGA("Yoga"), OTHER("Other")
}

internal data class Workout(
    val start: Long, val type: WorkoutType, val end: Long?, val averageHeartRate: Int?,
    val pausedAt: Long?, val pausedMillis: Long
) {
    fun seconds(now: Long) = ((end ?: pausedAt ?: now) - start - pausedMillis).coerceAtLeast(0) / 1000

    fun estimatedActiveKcal(profile: WorkoutEnergyProfile?, now: Long): Long? =
        if (type == WorkoutType.STRENGTH) profile?.strengthActiveKcal(seconds(now)) else null
}

internal class WorkoutEnergyProfile(weightKg: Double, bodyFatPercent: Double, basal: Double? = null) {
    val basalKcalPerDay = basal ?: 500 + 22 * weightKg * (1 - bodyFatPercent / 100) // Cunningham (1980)
    private val activeStrengthKcalPerMinute: Double

    init {
        val strengthMet = 3.5 // Adult Compendium 02054: resistance training, multiple exercises.
        val grossKcalPerMinute = strengthMet * 3.5 * weightKg / 200
        activeStrengthKcalPerMinute = (grossKcalPerMinute - basalKcalPerDay / 1_440).coerceAtLeast(0.0)
    }

    fun strengthActiveKcal(seconds: Long): Long = (activeStrengthKcalPerMinute * seconds / 60).roundToLong()
}

internal data class WorkoutLog(val active: Workout?, val recent: List<Workout>, val hasMore: Boolean)
