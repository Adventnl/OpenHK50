package org.openhk50.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutTest {
    @Test fun pausedSessionFreezesAndSavedDurationExcludesAllPauses() {
        val workout = Workout(1_000, WorkoutType.WALK, null, null, 51_000, 10_000)

        assertEquals(40L, workout.seconds(65_000))
        assertEquals(45L, workout.copy(pausedAt = null, pausedMillis = 24_000).seconds(70_000))
        assertEquals(45L, workout.copy(end = 70_000, pausedAt = null, pausedMillis = 24_000).seconds(90_000))
    }

    @Test fun strengthEnergyUsesActiveTimeAndThePersonalRestingBaseline() {
        val profile = WorkoutEnergyProfile(weightKg = 72.0, bodyFatPercent = 20.0)
        val workout = Workout(1_000, WorkoutType.STRENGTH, null, null, null, 600_000)

        assertEquals(0L, workout.estimatedActiveKcal(profile, 1_000))
        assertEquals(191L, workout.estimatedActiveKcal(profile, 4_201_000))
        assertEquals(95L, workout.copy(pausedAt = 2_401_000).estimatedActiveKcal(profile, 4_201_000))
        assertEquals(95L, workout.copy(end = 2_401_000).estimatedActiveKcal(profile, 4_201_000))
        assertEquals(184L, workout.estimatedActiveKcal(WorkoutEnergyProfile(72.0, 10.0), 4_201_000))
        assertNull(workout.estimatedActiveKcal(null, 4_201_000))
        assertNull(workout.copy(type = WorkoutType.WALK).estimatedActiveKcal(profile, 4_201_000))
    }
}
