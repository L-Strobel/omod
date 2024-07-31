package de.uniwuerzburg.omod.io.json

import de.uniwuerzburg.omod.core.models.ActivityType
import kotlinx.serialization.Serializable

/**
 * OMOD result format of on activity
 */
@Serializable
data class OutputActivity (
    val type: ActivityType,
    val stayTime: Double?,
    val lat: Double,
    val lon: Double,
    val dummyLoc: Boolean,
    val inFocusArea: Boolean
)