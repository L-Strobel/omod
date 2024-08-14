package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.utils.toDouble
import kotlinx.serialization.Serializable

@Serializable
data class ModeUtility (
    val mode: Mode,
    val timeCoeff: Double,
    val distanceCoeff: Double,
    val homGroupCoeff: Map<HomogeneousGrp, Double>,
    val mobGroupCoeff: Map<MobilityGrp, Double>,
    val ageGrpCoeff: Map<AgeGrp, Double>,
    val sexCoeff: Double, // IS Male?
    val carAvailableCoeff: Double,
    val activityCoeff: Map<ActivityType, Double>,
    val intercept: Double
) {
    fun calc(time: Double, distance: Double, activity: ActivityType, carAvailable: Boolean, agent: MobiAgent) : Double {
        return time * timeCoeff +
               distance * distanceCoeff +
               homGroupCoeff[agent.homogenousGroup]!! +
               mobGroupCoeff[agent.mobilityGroup]!! +
               ageGrpCoeff[agent.age]!! +
               sexCoeff * agent.sex.value +
               carAvailableCoeff * carAvailable.toDouble() +
               activityCoeff[activity]!! +
               intercept
    }
}