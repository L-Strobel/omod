package de.uniwuerzburg.omod.io.json

import kotlinx.serialization.Serializable

@Serializable
data class OutputFormat (
    val runParameters: Map<String, String>,
    val agents: List<OutputEntry>
)