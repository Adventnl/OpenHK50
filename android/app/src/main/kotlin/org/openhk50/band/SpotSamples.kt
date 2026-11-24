package org.openhk50.band

import org.openhk50.data.Metric

internal class SpotSamples(private val metric: Metric) {
    private val values = ArrayList<Int>(COUNT)

    val complete get() = values.size == COUNT

    fun add(value: Int): Boolean {
        if (value !in metric.intervalValues || complete) return false
        values.add(value)
        return true
    }

    fun median(): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }

    private companion object {
        const val COUNT = 6
    }
}
