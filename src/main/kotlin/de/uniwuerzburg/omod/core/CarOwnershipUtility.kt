package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.max

@Serializable
class CarOwnershipUtility (
    val homGroupCoeff: Map<HomogeneousGrp, Double>,
    val mobGroupCoeff: Map<MobilityGrp, Double>,
    val ageGrpCoeff: Map<AgeGrp, Double>,
    val intercept: Double
    ) {
    /**
     * Calculate utility.
     */
    fun calc(
        homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp
    ) : Double {
        return (homGroupCoeff[homogenousGroup] ?: 0.0) +
               (mobGroupCoeff[mobilityGroup] ?: 0.0) +
               (ageGrpCoeff[age] ?: 0.0) +
               intercept
    }
}
