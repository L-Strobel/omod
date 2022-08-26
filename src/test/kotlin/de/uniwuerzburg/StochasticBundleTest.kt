package de.uniwuerzburg

import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import kotlin.math.abs
import java.util.Random

internal class StochasticBundleTest {

    private val testWeights = doubleArrayOf(0.0, 2.2, 3.5, 1.0, 9.0, 12.2)
    private val testLogNorm = LogNorm(1.3, 8.0)
    private val rng = Random()

    @Test
    fun createCumDist() {
        val expected = 1.0
        assertEquals(expected, createCumDist(testWeights).last())
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

    @Test
    fun logNormDensity(){
        val tolerance = 1E-5
        val testX = listOf(0.0, 0.3, 1.0, 2.0, 3.12345, 100.0, 1E+9)
        val scipyResults = listOf(0.0, 0.04213322395387047, 0.08538237709992151, 0.08689685601963477,
                                  0.07562705885378117, 0.00046482880498182694, 6.683802855622367E-55)
        for (i in testX.indices) {
            assert(abs(scipyResults[i] - testLogNorm.density(testX[i])) < tolerance)
        }

    }
}