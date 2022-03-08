package de.uniwuerzburg

import java.util.*

object StochasticBundle {
    private val random: Random = Random()

    // Create cumulative distribution from weights
    fun createCumDist(weights: IntArray): DoubleArray {
        return createCumDist(weights.map { it.toDouble() }.toDoubleArray())
    }
    fun createCumDist(weights: DoubleArray): DoubleArray {
        val cumDist = DoubleArray(weights.size)
        var cumSum = 0.0
        for (i in weights.indices) {
            cumSum += weights[i]
            cumDist[i] = cumSum
        }
        return cumDist
    }

    // Sample cumulative distribution
    fun sampleCumDist(cumDist: DoubleArray): Int {
        val thresh: Double = random.nextDouble() * cumDist[cumDist.size - 1]
        var i = 0
        while (i < cumDist.size - 1) {
            if (thresh < cumDist[i]) break
            i++
        }
        return i
    }

    // Create and immediately sample cumulative distribution
    fun createAndSampleCumDist(weights: DoubleArray) : Int {
        return sampleCumDist(createCumDist(weights))
    }
}
