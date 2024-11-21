package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.MobiAgent
import de.uniwuerzburg.omod.core.models.PopStratum
import java.util.*

class CarOwnershipFixedProbability(
    private val minDrivingAge: Int
): CarOwnership {
    override fun determine(agent: MobiAgent, stratum: PopStratum, rng: Random) : Boolean {
       return if ((agent.age != null) and (agent.age!! < minDrivingAge)) {
           false
       } else {
           rng.nextDouble() < stratum.carOwnership
       }
    }
}