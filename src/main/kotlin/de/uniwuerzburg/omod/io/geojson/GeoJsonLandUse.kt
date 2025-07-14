package de.uniwuerzburg.omod.io.geojson

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("FeatureCollection")
data class GeoJsonLandUse (
    val features: List<GeoJsonFeaturesLandUse>
)