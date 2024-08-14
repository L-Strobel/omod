package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.MobiAgent
import kotlinx.coroutines.CoroutineDispatcher
import java.util.*

interface ModeChoice {
    fun doModeChoice(agents: List<MobiAgent>, mainRng: Random, dispatcher: CoroutineDispatcher) : List<MobiAgent>
}