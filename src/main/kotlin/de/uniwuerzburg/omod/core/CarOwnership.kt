package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.MobiAgent
import de.uniwuerzburg.omod.core.models.PopStratum
import java.util.*

interface CarOwnership {
    fun determine(agent: MobiAgent, stratum: PopStratum, rng: Random) : Boolean
}