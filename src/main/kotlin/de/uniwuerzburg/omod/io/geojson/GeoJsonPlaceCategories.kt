package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.Serializable

@Serializable
data class GeoJsonPlaceCategories(
    val primary: String,
    val alternate: List<String>? = null
)