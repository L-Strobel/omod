package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.MobiAgent
import kotlinx.coroutines.CoroutineDispatcher
import java.util.*

/**
 * Determine the mode of trips.
 */
interface ModeChoice {
    /**
     * Determine the mode of each trip and calculate the distance and time.
     *
     * @param agents Agents with trips (usually the trips have an UNDEFINED mode at this point)
     * @param mainRng Random number generator of the main thread
     * @param dispatcher Coroutine dispatcher used for concurrency
     * @return agents. Now their trips have specified modes.
     */
    fun doModeChoice(agents: List<MobiAgent>, mainRng: Random, dispatcher: CoroutineDispatcher) : List<MobiAgent>
}