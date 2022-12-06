package de.uniwuerzburg.omod.core

import kotlinx.serialization.Serializable

/**
 * Definition of the agent population in terms of sociodemographic features
 */
@Serializable
data class PopulationDef (
    val homogenousGroup: Map<HomogeneousGrp, Double>,
    val mobilityGroup: Map<MobilityGrp, Double>,
    val age: Map<AgeGrp, Double>
)
