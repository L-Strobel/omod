package de.uniwuerzburg.omod.io.json

import de.uniwuerzburg.omod.core.models.Weekday
import kotlinx.serialization.Serializable

/**
 * OMOD result format of o day
 */
@Serializable
data class OutputDiary (
    val day: Int,
    val dayType: Weekday,
    val activities: List<OutputActivity>
)