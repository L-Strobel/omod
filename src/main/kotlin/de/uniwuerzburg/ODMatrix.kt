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

data class ODZone (
    val name: String,
    var inFocusArea: Boolean = false
) {
    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ODZone

        if (name != other.name) return false
        assert(other.inFocusArea == inFocusArea) {"Somehow there are two different version of one OD-Zone"}

        return true
    }
}


class ODMatrix (odFile: File, factory: GeometryFactory) {
    val rows: Map<ODZone, ODRow>

    init {
        rows = mutableMapOf()

        // Read OD
        val geoJson: GeoJsonFeatureCollection = Json{ ignoreUnknownKeys = true }
            .decodeFromString(odFile.readText(Charsets.UTF_8))

        // Get zones
        val odZonesStr = mutableSetOf<String>()
        for (entry in geoJson.features) {
            val properties = entry.properties as GeoJsonODProperties
            odZonesStr.add(properties.origin)
            odZonesStr.addAll(properties.destinations.keys)
        }
        val odZones = odZonesStr.associateWith { ODZone(it) }

        for (entry in geoJson.features) {
            val properties = entry.properties as GeoJsonODProperties
            val origin = odZones[properties.origin]!!
            val geometry = entry.geometry.toJTS(factory)
            val originActivity = properties.origin_activity
            val destinationActivity = properties.destination_activity
            val destinations = properties.destinations.mapKeys { odZones[it.key]!! }

            rows[origin] = ODRow(origin, originActivity, destinationActivity, destinations, geometry)
        }

        // Check if OD is valid
        require(setOf(rows.values.map { it.originActivity }).size == 1)
            { "All origin activities must be the same!" }
        require(setOf(rows.values.map { it.destinationActivity }).size == 1)
            { "All destination activities must be the same!" }

        // Valid activities? Currently, only HOME->WORK are allowed
        require(rows.values.all { it.originActivity == ActivityType.HOME })
            { "Only calibration with commuting data is currently supported! " +
              "This means OD-Matrices with Activities HOME->WORK." }
        require(rows.values.all { it.destinationActivity == ActivityType.WORK })
            { "Only calibration with commuting data is currently supported! " +
              "This means OD-Matrices with Activities HOME->WORK." }
    }
}