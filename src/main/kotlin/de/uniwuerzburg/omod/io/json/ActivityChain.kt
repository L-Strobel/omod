package de.uniwuerzburg.omod.io.json

import de.uniwuerzburg.omod.core.models.ActivityType
import kotlinx.serialization.Serializable

/**
 * Json storage format.
 * For activity chain.
 * Contains the corresponding weight in the probability distribution and
 * the Gaussian Mixture of the dwell time distribution.
 */
@Serializable
data class ActivityChain(
    val chain: List<ActivityType>,
    val weight: Double,
    val gaussianMixture: GaussianMixture?
)