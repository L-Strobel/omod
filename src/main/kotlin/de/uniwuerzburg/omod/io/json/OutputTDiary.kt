package de.uniwuerzburg.omod.io.json

import kotlinx.serialization.Serializable

/**
 * Output format of day in assignment
 */
@Serializable
data class OutputTDiary (
    val day: Int,
    val trips: List<OutputTrip>
)