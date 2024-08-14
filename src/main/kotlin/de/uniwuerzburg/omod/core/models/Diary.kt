package de.uniwuerzburg.omod.core.models

/**
 * Simulation result. Daily activity dairy.
 *
 * @param day Number of the day the diary is for
 * @param dayType Type of day
 * @param activities Activities that the agent wants to conduct on the day
 */
data class Diary (
    val day: Int,
    val dayType: Weekday,
    val activities: List<Activity>,
) {
    var trips: List<Trip>? = null
}