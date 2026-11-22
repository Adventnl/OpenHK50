package org.openhk50.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutTest {
    @Test fun pausedSessionFreezesAndSavedDurationExcludesAllPauses() {
        val workout = Workout(1_000, WorkoutType.WALK, null, null, 51_000, 10_000)

        assertEquals(40L, workout.seconds(65_000))
        assertEquals(45L, workout.copy(pausedAt = null, pausedMillis = 24_000).seconds(70_000))
        assertEquals(45L, workout.copy(end = 70_000, pausedAt = null, pausedMillis = 24_000).seconds(90_000))
    }
}
