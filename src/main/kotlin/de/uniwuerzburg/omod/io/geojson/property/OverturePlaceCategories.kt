package de.uniwuerzburg.omod.io.geojson.property

import kotlinx.serialization.Serializable

@Serializable
data class OverturePlaceCategories(
    val primary: String,
    val alternate: List<String>? = null
)