package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.MobiAgent
import de.uniwuerzburg.omod.core.models.PopStratum
import java.util.*

/**
 * Determines car ownership of an agent.
 */
interface CarOwnership {
    fun determine(agent: MobiAgent, stratum: PopStratum, rng: Random) : Boolean
}