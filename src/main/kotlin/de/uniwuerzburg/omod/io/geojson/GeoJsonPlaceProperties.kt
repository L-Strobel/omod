package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.Serializable

@Serializable
data class GeoJsonPlaceProperties(
    val confidence: Double,
    val categories: GeoJsonPlaceCategories
) : GeoJsonProperties()