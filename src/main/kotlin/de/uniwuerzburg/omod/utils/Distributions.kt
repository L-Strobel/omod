package de.uniwuerzburg.omod.utils

import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.CholeskyDecomposition
import java.util.*

/**
 * Create cumulative distribution from counts for without replacement sampling.
 * Values are counts of observations.
 *
 * @param counts Number of observations
 * @return Cumulative distribution
 */
@Suppress("unused")
fun createCumDistWOR(counts: IntArray): IntArray {
    assert(counts.all { it >= 0 })
    val cumDist = IntArray(counts.size)
    var cumSum = 0
    for (i in counts.indices) {
        cumSum += counts[i]
        cumDist[i] = cumSum
    }
    return cumDist
}

/**
 * Sample from cumulative distribution without replacement
 *
 * @param cumDistWOR Cumulative distribution
 * @param rng Random number generator
 * @return Index of sample
 */
fun sampleCumDistWOR(cumDistWOR: IntArray, rng: Random): Int {
    assert(cumDistWOR.all { it >= 0 })
    val thresh = rng.nextInt(cumDistWOR.last())

    var i = 0
    while (i < cumDistWOR.size - 1) {
        if (thresh < cumDistWOR[i]) {
            cumDistWOR[i] -= 1 // TODO: [BUG] 1 Should also be subtracted from all following values.
            break
        }
        i++
    }
    return i
}

/**
 * Create cumulative distribution from weights.
 * Integer weights version.
 * @param weights Probabilistic weights
 * @return Cumulative distribution
 */
@Suppress("unused")
fun createCumDist(weights: IntArray): DoubleArray {
    return createCumDist(weights.map { it.toDouble() }.toDoubleArray())
}
/**
 * Create cumulative distribution from weights.
 * Double weights version.
 * @param weights Probabilistic weights
 * @return Cumulative distribution
 */
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

/**
 * Sample from cumulative distribution
 *
 * @param cumDist Cumulative distribution
 * @param rng Random number generator
 * @return Index of sample
 */
fun sampleCumDist(cumDist: DoubleArray, rng: Random): Int {
    val thresh = rng.nextDouble()
    var i = 0
    while (i < cumDist.size - 1) {
        if (thresh < cumDist[i]) break
        i++
    }
    return i
}

/**
 * Create and immediately sample cumulative distribution
 *
 * @param weights Probabilistic weights
 * @param rng Random number generator
 * @return Index of sample
 */
@Suppress("unused")
fun createAndSampleCumDist(weights: DoubleArray, rng: Random) : Int {
    return sampleCumDist(createCumDist(weights), rng)
}

/**
 * Sample from multidimensional gaussian distribution
 *
 * @param means Mean vector
 * @param covariances Covariance matrix
 * @param rng Random number generator
 * @return Sample
 */
fun sampleNDGaussian(means: DoubleArray, covariances: Array<DoubleArray>, rng: Random): DoubleArray {
    require(covariances.size == means.size) { "Dimension mismatch !" }
    val dim = means.size

    // Get Cholesky decomposition; Symmetry tolerance is quite high ... Maybe I should investigate why scikit-learn
    // returns such asymmetric matrices
    val l = CholeskyDecomposition(Array2DRowRealMatrix(covariances), 0.1, 1.0E-10).l

    // Get independent Gaussian's
    val u = DoubleArray(dim) { rng.nextGaussian() }

    // Do x = mean + l * u
    val x = l.operate(u)
    for (i in 0 until dim) {
        x[i] += means[i]
    }
    return x
}
