package de.uniwuerzburg.omod.io.geojson.property

import de.uniwuerzburg.omod.io.geojson.GeoJsonProperties
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OvertureLandUseProperties(
    @SerialName("class")
    val landUseClass: String
) : GeoJsonProperties()