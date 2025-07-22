package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.Serializable

/**
 * GeoJSOn feature collection
 *
 * @param type Always "FeatureCollection"
 * @param features List of contained GeoJSON features
 */
@Serializable
data class GeoJsonFeatureCollection<T: GeoJsonProperties> (
    val type: String = "FeatureCollection",
    val features: List<GeoJsonFeature<T>>
)