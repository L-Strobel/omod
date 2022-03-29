package de.uniwuerzburg

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.CholeskyDecomposition
import java.util.*
import kotlin.math.*

object StochasticBundle {
    private val random: Random = Random()

    // Create cumulative distribution from weights
    fun createCumDist(weights: IntArray): DoubleArray {
        return createCumDist(weights.map { it.toDouble() }.toDoubleArray())
    }
    fun createCumDist(weights: DoubleArray): DoubleArray {
        require(weights.all { it >= 0 })
        val total = weights.sum()

        val cumDist = DoubleArray(weights.size)
        var cumSum = 0.0
        for (i in weights.indices) {
            cumSum += weights[i]
            cumDist[i] = cumSum / total
        }

        return cumDist
    }

    // Sample cumulative distribution
    fun sampleCumDist(cumDist: DoubleArray): Int {
        val thresh = random.nextDouble()
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

    // Sample from multidimensional gaussian
    fun sampleNDGaussian(means: DoubleArray, covariances: Array<DoubleArray>): DoubleArray {
        require(covariances.size == means.size) { "Dimension mismatch !" }
        val dim = means.size

        // Get Cholesky decomposition
        val l = CholeskyDecomposition(Array2DRowRealMatrix(covariances), 0.001, 1.0E-10).l

        // Get independent gaussians
        val u = DoubleArray(dim) { random.nextGaussian() }

        // Do x = mean + l * u
        val x = l.operate(u)
        for (i in 0 until dim) {
            x[i] += means[i]
        }
        return x
    }

    /**
     * Lognormal distribution
     * @param shape Shape parameter as defined by scipy
     * @param scale Scale parameter as defined by scipy
     */
    @Suppress("MemberVisibilityCanBePrivate")
    class LogNorm(val shape: Double, val scale: Double) {
        fun sample(): Double {
            return exp(random.nextGaussian() * shape) * scale
        }
        fun density(x:Double): Double{
            require(x >= 0) { "x must be positive! Given value for x: $x"}
            return if (x == 0.0) {
                0.0 // Lognormal distribution converges to zero at zero
            } else {
                val xScaled = x / scale
                1 / (shape * xScaled * sqrt(2 * PI)) * exp(- ln(xScaled).pow(2) / (2 * shape.pow(2))) / scale
            }
        }
    }
}
