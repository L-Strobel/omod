package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.Serializable

/**
 * GeoJSON feature. Generic entry in a feature collection.
 *
 * @param type Type of feature
 * @param geometry Geometry of the feature
 * @param properties Other information about the feature
 */
@Serializable
data class GeoJsonFeature (
    val type: String = "Feature",
    val geometry: GeoJsonGeom,
    val properties: GeoJsonProperties
)