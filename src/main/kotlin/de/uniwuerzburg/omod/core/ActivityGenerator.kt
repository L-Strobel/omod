package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*

interface ActivityGenerator {
    fun getActivityChain(agent: MobiAgent, weekday: Weekday, from: ActivityType) : List<ActivityType>
    fun getStayTimes(activityChain: List<ActivityType>, agent: MobiAgent, weekday: Weekday) : List<Double?>
}