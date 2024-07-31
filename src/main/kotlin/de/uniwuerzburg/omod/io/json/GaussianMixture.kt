package de.uniwuerzburg.omod.io.json

import kotlinx.serialization.Serializable

/**
 * Json storage format.
 * Gaussian Mixture of dwell time distribution.
 */
@Serializable
class GaussianMixture(
    val weights: List<Double>,
    val means: List<List<Double>>,
    val covariances: List<List<List<Double>>>
)