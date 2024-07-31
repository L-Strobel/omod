package de.uniwuerzburg.omod.io.json

import kotlinx.serialization.Serializable

/**
 * Output format of all assignment information of an agent
 */
@Serializable
data class OutputTEntry (
    val id: Int,
    val days: List<OutputTDiary>
)