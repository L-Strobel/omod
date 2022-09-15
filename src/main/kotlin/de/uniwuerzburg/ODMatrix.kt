package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

/**
 * Row in the OD-Matrix
 */
data class ODRow (
    val origin: ODZone,
    val originActivity: ActivityType,
    val destinationActivity: ActivityType,
    val destinations: Map<ODZone, Double>,
    val geometry: Geometry
)

class ODMatrix (odFile: File, factory: GeometryFactory) {
    val rows: Map<ODZone, ODRow>

    init {
        rows = mutableMapOf()

        // Read OD
        val geoJson: GeoJsonFeatureCollection = Json{ ignoreUnknownKeys = true }
            .decodeFromString(odFile.readText(Charsets.UTF_8))

        for (entry in geoJson.features) {
            val properties = entry.properties as GeoJsonODProperties
            val origin = ODZone(properties.origin)
            val geometry = entry.geometry.toJTS(factory)
            val originActivity = properties.origin_activity
            val destinationActivity = properties.destination_activity
            val destinations = properties.destinations.mapKeys { ODZone(it.key) }

            rows[origin] = ODRow(origin, originActivity, destinationActivity, destinations, geometry)
        }

        // Check if OD is valid
        require(setOf(rows.values.map { it.originActivity }).size == 1)
            { "All origin activities must be the same!" }
        require(setOf(rows.values.map { it.destinationActivity }).size == 1)
            { "All destination activities must be the same!" }

        // Valid activities? Currently, only HOME->WORK are allowed
        require(rows.values.all { it.originActivity == ActivityType.HOME })
            { "Only OD-Matrices with Activities HOME->WORK are currently supported!" }
        require(rows.values.all { it.destinationActivity == ActivityType.WORK })
            { "Only OD-Matrices with Activities HOME->WORK are currently supported!" }
    }
}