package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.max

@Serializable
data class ModeUtility (
    val mode: Mode,
    val timeCoeff: Double,
    val logTimeCoeff: Double,
    val distanceCoeff: Double,
    val logDistanceCoeff: Double,
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
        val timeClipped = max(time, 1.0) // Minimum time: 1 minute
        val distanceClipped = max(distance, 0.001) // Minimum distance: 1 meter

        return timeClipped * timeCoeff +
               ln(timeClipped) * logTimeCoeff +
               distanceClipped * distanceCoeff +
               ln(distanceClipped) * logDistanceCoeff +
               (homGroupCoeff[agent.homogenousGroup] ?: 0.0) +
               (mobGroupCoeff[agent.mobilityGroup] ?: 0.0) +
               (ageGrpCoeff[agent.age] ?: 0.0) +
               (sexCoeff[agent.sex] ?: 0.0) +
               (carAvailableCoeff[carAvailable] ?: 0.0) +
               (activityCoeff[activity] ?: 0.0) +
               intercept
    }
}