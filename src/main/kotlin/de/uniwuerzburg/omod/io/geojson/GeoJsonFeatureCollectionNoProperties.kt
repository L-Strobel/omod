package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Work around structure for json objects with empty properties = {} and raw GeometryCollections
 * See GeoJsonFeatureCollection.
 */
@Serializable
@SerialName("FeatureCollection")
data class GeoJsonFeatureCollectionNoProperties (
    val features: List<GeoJsonFeatureNoProperties>
) : GeoJsonNoProperties