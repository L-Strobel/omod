package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import java.util.*

/**
 * Determines the types and durations of activities an agent conducts on a given day.
 */
interface ActivityGenerator {
    /**
     * Determine the activity chain of an agent.
     *
     * @param agent The agent
     * @param weekday The weekday
     * @param from The yesterday's last activity
     * @param rng Random number generator
     * @return List of activities conducted by the agent today
     */
    fun getActivityChain(agent: MobiAgent, weekday: Weekday, from: ActivityType, rng: Random) : List<ActivityType>

    /**
     * Get the stay times at each activity.
     *
     * @param activityChain The activities the agent will undertake that day
     * @param agent The agent
     * @param weekday The weekday
     * @param rng Random number generator
     * @return Stay times at each activity
     */
    fun getStayTimes(activityChain: List<ActivityType>, agent: MobiAgent, weekday: Weekday, rng: Random) : List<Double?>
}