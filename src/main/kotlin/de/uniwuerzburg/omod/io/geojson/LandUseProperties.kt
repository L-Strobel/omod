package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LandUseProperties(
    @SerialName("class")
    val landUseClass: String
)