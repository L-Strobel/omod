package de.uniwuerzburg.omod.core.models

/**
 * A combination of socio demographic features. Used in agent creation process.
 */
class SocioDemFeatureSet (
    val hom: HomogeneousGrp,
    val mob: MobilityGrp,
    val age: Int?,
    val sex: Sex
)