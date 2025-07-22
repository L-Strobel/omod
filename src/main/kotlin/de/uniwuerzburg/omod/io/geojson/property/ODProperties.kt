package de.uniwuerzburg.omod.io.geojson.property

import de.uniwuerzburg.omod.core.models.ActivityType
import de.uniwuerzburg.omod.io.geojson.GeoJsonProperties
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GeoJSON representation of an origin-destination matrix entry.
 *
 * @param origin Name of the origin
 * @param origin_activity Activity conducted at origin
 * @param destination_activity Activity conducted at destination
 * @param destinations Key: Name of destination, Value: Number of trips going to destination
 */
@Serializable
@SerialName("ODEntry")
data class ODProperties (
    val origin: String,
    val origin_activity: ActivityType,
    val destination_activity: ActivityType,
    val destinations: Map<String, Double>
) : GeoJsonProperties()