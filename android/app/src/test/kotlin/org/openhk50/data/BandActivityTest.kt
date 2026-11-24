package org.openhk50.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandActivityTest {
    @Test fun snapshotUsesBigEndianCountsAndMilliKcal() {
        val frame = byteArrayOf(0x48, 0, 0x0b, 0x00, 0, 0, 0, 0x04, 0x58, 0x11, 0, 0x07, 0x00, 0, 0, 0)
        assertEquals(BandActivity(2_816, 284_689, 1_792), BandActivity.fromHk50Summary(frame))
        frame[1] = -1
        frame[2] = -1
        frame[3] = -1
        assertNull(BandActivity.fromHk50Summary(frame))
    }

    @Test fun legacyCaloriesDivideAfterAggregationAndCalibrationUsesTheWalk() {
        assertEquals(300L, Metric.CALORIES.fromActivityStorage(30_000, .4))
        assertEquals(0.4, stepFactor(100, 250)!!, 1e-6)
        assertEquals(1_200L, Metric.STEPS.fromActivityStorage(3_000, .4))
        assertNull(stepFactor(10, 20))
    }
}
