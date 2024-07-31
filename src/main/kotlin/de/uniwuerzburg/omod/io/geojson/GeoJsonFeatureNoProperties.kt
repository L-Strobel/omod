package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.Serializable

/**
 * Work around structure for json objects with empty properties = {} and raw GeometryCollections
 * See GeoJsonFeature.
 */
@Serializable
data class GeoJsonFeatureNoProperties (
    val type: String = "Feature",
    val geometry: GeoJsonGeom
)