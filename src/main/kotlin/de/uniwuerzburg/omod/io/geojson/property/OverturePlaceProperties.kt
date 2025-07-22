package de.uniwuerzburg.omod.io.geojson.property

import de.uniwuerzburg.omod.io.geojson.GeoJsonProperties
import kotlinx.serialization.Serializable

@Serializable
data class OverturePlaceProperties(
    val confidence: Double,
    val categories: OverturePlaceCategories
) : GeoJsonProperties()