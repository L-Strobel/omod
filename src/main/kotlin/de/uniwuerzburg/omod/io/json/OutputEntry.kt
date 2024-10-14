package de.uniwuerzburg.omod.io.json

import de.uniwuerzburg.omod.core.models.AgeGrp
import de.uniwuerzburg.omod.core.models.HomogeneousGrp
import de.uniwuerzburg.omod.core.models.MobilityGrp
import de.uniwuerzburg.omod.core.models.Sex
import kotlinx.serialization.Serializable

/**
 * OMOD result format of on agent
 */
@Serializable
data class OutputEntry (
    val id: Int,
    val homogenousGroup: HomogeneousGrp,
    val mobilityGroup: MobilityGrp,
    val age: Int?,
    val sex: Sex,
    val carAccess: Boolean,
    val mobilityDemand: List<OutputDiary>
)