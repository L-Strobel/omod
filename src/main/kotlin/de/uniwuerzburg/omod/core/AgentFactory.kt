package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.AggLocation
import de.uniwuerzburg.omod.core.models.MobiAgent
import java.util.*

interface AgentFactory {
    fun createAgents(
        share: Double, zones: List<AggLocation>, populateBufferArea: Boolean, rng: Random
    ) : List<MobiAgent>
    fun createAgents(
        nFocus: Int, zones: List<AggLocation>, populateBufferArea: Boolean, rng: Random
    ) : List<MobiAgent>
}