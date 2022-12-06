package de.uniwuerzburg.omod.core

import org.junit.jupiter.api.Test
import java.util.Random

internal class StochasticBundleTest {

    private val testWeights = doubleArrayOf(0.0, 2.2, 3.5, 1.0, 9.0, 12.2)
    private val rng = Random()

    @Test
    fun createCumDist() {
        val expected = 1.0
        assert(expected == createCumDist(testWeights).last())
    }

    @Test
    fun sampleCumDist() {
        val distr = createCumDist(testWeights)
        val samples = IntArray(100) { sampleCumDist(distr, rng) }
        assert(0 <= samples.minOrNull()!!)
        assert(testWeights.size > samples.maxOrNull()!!)
    }

    @Test
    fun sampleCumDistTestZeroWeight() {
        val distr = createCumDist(testWeights)
        val samples = IntArray(100) { sampleCumDist(distr, rng) }
        assert(!samples.contains(0))
    }
}