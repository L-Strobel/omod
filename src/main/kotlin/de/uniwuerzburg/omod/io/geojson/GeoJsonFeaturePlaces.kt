package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.Serializable

@Serializable
data class GeoJsonFeaturePlaces(
    val type: String = "Feature",
    val geometry: GeoJsonGeom,
    val properties: GeoJsonPlaceProperties
)