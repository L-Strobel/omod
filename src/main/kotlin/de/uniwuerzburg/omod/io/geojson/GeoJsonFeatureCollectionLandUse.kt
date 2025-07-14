package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeoJsonFeaturesLandUse (
    val type: String = "Feature",
    val geometry: GeoJsonGeom,
    val properties: LandUseProperties
)
