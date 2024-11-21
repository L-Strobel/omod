package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import java.util.*
import kotlin.math.exp

class CarOwnershipMNL(
    private val carOwnershipUtility: CarOwnershipUtility,
    private val minDrivingAge: Int
) : CarOwnership {
    override fun determine(agent: MobiAgent, stratum: PopStratum, rng: Random) : Boolean {
        return if ((agent.age != null) and (agent.age!! < minDrivingAge)) {
            false
        } else {
            sampleOwnership( agent.homogenousGroup, agent.mobilityGroup, agent.ageGrp, rng )
        }
    }

    private fun sampleOwnership(
        homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp, rng: Random
    ) : Boolean {
        val utility = carOwnershipUtility.calc( homogenousGroup, mobilityGroup, age )
        val p = 1 / (1 + exp(-utility))
        return rng.nextDouble() < p
    }
}