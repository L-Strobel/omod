package de.uniwuerzburg.omod.core.models

/**
 * Agent.
 *
 * @param id ID
 * @param homogenousGroup Hom. group of agent (Working person etc.)
 * @param mobilityGroup Mob. group of agent (Car user etc.)
 * @param age Age group of agent
 * @param home Home location of agent
 * @param work Work location of agent (Is also defined if the agent does not work)
 * @param school School location of agent (Is also defined if the agent does not go to school)
 * @param sex Sex of agent
 * @param mobilityDemand Simulation result of agent
 *
 */
data class MobiAgent (
    val id: Int,
    val homogenousGroup: HomogeneousGrp,
    val mobilityGroup: MobilityGrp,
    val age: Int?,
    val home: LocationOption,
    val work: LocationOption,
    val school: LocationOption,
    val sex: Sex,
    var carAccess: Boolean = false,
    val mobilityDemand: MutableList<Diary> = mutableListOf()
) {
    val ageGrp = AgeGrp.fromInt(age)
}