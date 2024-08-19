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
    val sexCoeff: Map<Sex, Double>,
    val carAvailableCoeff: Map<Boolean, Double>,
    val activityCoeff: Map<ActivityType, Double>,
    val intercept: Double
) {
    fun calc(
        time: Double, distance: Double, activity: ActivityType, carAvailable: Boolean?, agent: MobiAgent
    ) : Double {
        return time * timeCoeff +
               distance * distanceCoeff +
                (homGroupCoeff[agent.homogenousGroup] ?: 0.0) +
                (mobGroupCoeff[agent.mobilityGroup] ?: 0.0) +
                (ageGrpCoeff[agent.age] ?: 0.0) +
                (sexCoeff[agent.sex] ?: 0.0) +
                (carAvailableCoeff[carAvailable] ?: 0.0) +
                (activityCoeff[activity] ?: 0.0) +
               intercept
    }
}