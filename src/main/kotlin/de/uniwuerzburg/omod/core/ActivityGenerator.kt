package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import java.util.*

interface ActivityGenerator {
    fun getActivityChain(agent: MobiAgent, weekday: Weekday, from: ActivityType, rng: Random) : List<ActivityType>
    fun getStayTimes(activityChain: List<ActivityType>, agent: MobiAgent, weekday: Weekday, rng: Random) : List<Double?>
}