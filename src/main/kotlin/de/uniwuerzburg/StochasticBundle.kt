package de.uniwuerzburg

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.CholeskyDecomposition
import java.util.*
import kotlin.math.*

// Create cumulative distribution from weights
@Suppress("unused")
fun createCumDist(weights: IntArray): DoubleArray {
    return createCumDist(weights.map { it.toDouble() }.toDoubleArray())
}
fun createCumDist(weights: DoubleArray): DoubleArray {
    assert(weights.all { it >= 0 })
    val cumDist = DoubleArray(weights.size)
    var cumSum = 0.0
    for (i in weights.indices) {
        cumSum += weights[i]
        cumDist[i] = cumSum
    }
    // Normalize
    for (i in cumDist.indices) {
        cumDist[i] /= cumSum
    }
    return cumDist
}

// Sample cumulative distribution
fun sampleCumDist(cumDist: DoubleArray, rng: Random): Int {
    val thresh = rng.nextDouble()
    var i = 0
    while (i < cumDist.size - 1) {
        if (thresh < cumDist[i]) break
        i++
    }
    return i
}

// Create and immediately sample cumulative distribution
@Suppress("unused")
fun createAndSampleCumDist(weights: DoubleArray, rng: Random) : Int {
    return sampleCumDist(createCumDist(weights), rng)
}

// Sample from multidimensional gaussian
fun sampleNDGaussian(means: DoubleArray, covariances: Array<DoubleArray>, rng: Random): DoubleArray {
    require(covariances.size == means.size) { "Dimension mismatch !" }
    val dim = means.size

    // Get Cholesky decomposition; Symmetry tolerance is quite high ... Maybe I should investigate why scikit-learn
    // returns such asymmetric matrices
    val l = CholeskyDecomposition(Array2DRowRealMatrix(covariances), 0.01, 1.0E-10).l

    // Get independent gaussians
    val u = DoubleArray(dim) { rng.nextGaussian() }

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
    @Suppress("unused")
    fun sample(rng: Random): Double {
        return exp(rng.nextGaussian() * shape) * scale
    }
    fun density(x: Double): Double{
        require(x >= 0) { "x must be positive! Given value for x: $x"}
        return if (x == 0.0) {
            0.0 // Lognormal distribution converges to zero at zero
        } else {
            val xScaled = x / scale
            1 / (shape * xScaled * sqrt(2 * PI)) * exp(- ln(xScaled).pow(2) / (2 * shape.pow(2))) / scale
        }
    }
}

object DestinationChoiceMN {

}