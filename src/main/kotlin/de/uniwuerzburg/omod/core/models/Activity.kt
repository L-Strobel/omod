package de.uniwuerzburg.omod.core.models

/**
 * Activity.
 *
 * @param type Type of the activity (HOME, WORK, SCHOOL, etc.)
 * @param stayTime Preferred duration of the activity. Unit: Minutes.
 * @param location Location where the activity takes place
 */
data class Activity (
    val type: ActivityType,
    val stayTime: Double?,
    val location: LocationOption
)