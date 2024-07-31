package de.uniwuerzburg.omod.core.models

import kotlinx.serialization.Serializable

/**
 * Definition of the agent population in terms of sociodemographic features.
 *
 * @param homogenousGroup Probabilities of each hom. group category
 * @param mobilityGroup Probabilities of each mob. group category
 * @param age Probabilities of each age group category
 */
@Serializable
data class PopulationDef (
    val homogenousGroup: Map<HomogeneousGrp, Double>,
    val mobilityGroup: Map<MobilityGrp, Double>,
    val age: Map<AgeGrp, Double>
)
