package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.AggLocation
import de.uniwuerzburg.omod.core.models.MobiAgent

interface AgentFactory {
    fun createAgents(share: Double, zones: List<AggLocation>) : List<MobiAgent>
    fun createAgents(nFocus: Int, zones: List<AggLocation>, populateBufferArea: Boolean) : List<MobiAgent>
}