package org.openhk50.data

import kotlin.math.exp
import kotlin.math.roundToInt

internal data class HeartSample(val time: Long, val bpm: Int, val spot: Boolean = false)
internal data class NightVitals(val sleepMinutes: Int, val heartBpm: Int?, val hrv: Int?)
internal data class HealthInsights(
    val night: NightVitals?,
    val baselineNights: Int,
    val recovery: Int?,
    val strain: Int?,
    val heartMinutes: Int
)

internal fun calculateInsights(night: NightVitals?, history: List<NightVitals>, heart: List<HeartSample>): HealthInsights {
    val baseline = history.filter { it.heartBpm != null && it.hrv != null }
    val baselinePulse = baseline.mapNotNull { it.heartBpm }.median()
    val resting = history.mapNotNull { it.heartBpm }.takeIf { it.size >= 7 }?.median()
    val hrv = baseline.mapNotNull { it.hrv }.median()
    val recovery = if (baseline.size >= 7 && baselinePulse != null && hrv != null &&
        night?.heartBpm != null && night.hrv != null) {
        val sleep = (night.sleepMinutes / 480.0).coerceAtMost(1.0)
        val pulse = (baselinePulse.toDouble() / night.heartBpm).coerceAtMost(1.0)
        val variability = (night.hrv.toDouble() / hrv).coerceAtMost(1.0)
        (100 * (.30 * sleep + .30 * pulse + .40 * variability)).roundToInt()
    } else null

    var minutes = 0.0
    var load = 0.0
    for ((first, next) in heart.zipWithNext()) {
        val elapsed = (next.time - first.time) / 60_000.0
        if (elapsed !in 1.0..16.0) continue
        minutes += elapsed
        if (resting != null) {
            val aboveRest = (first.bpm + next.bpm) / 2.0 - resting
            if (aboveRest >= 20) load += elapsed * (aboveRest / 60) * (aboveRest / 60)
        }
    }
    val strain = if (resting != null && minutes >= 30) (100 * (1 - exp(-load / 60))).roundToInt() else null
    return HealthInsights(night, baseline.size, recovery, strain, minutes.roundToInt())
}

internal fun List<Int>.median(): Int? {
    if (isEmpty()) return null
    val sorted = sorted()
    return (sorted[(size - 1) / 2] + sorted[size / 2]) / 2
}
