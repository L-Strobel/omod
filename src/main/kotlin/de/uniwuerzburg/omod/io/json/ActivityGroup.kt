package de.uniwuerzburg.omod.io.json

import de.uniwuerzburg.omod.core.models.AgeGrp
import de.uniwuerzburg.omod.core.models.HomogeneousGrp
import de.uniwuerzburg.omod.core.models.MobilityGrp
import de.uniwuerzburg.omod.core.models.Weekday
import kotlinx.serialization.Serializable

/**
 * Json storage format.
 * For activity chain probability distribution container.
 */
@Serializable
data class ActivityGroup(
    val weekday: Weekday,
    val homogenousGroup: HomogeneousGrp,
    val mobilityGroup: MobilityGrp,
    val age: AgeGrp,
    val sampleSize: Int,
    val activityChains: List<ActivityChain>
)