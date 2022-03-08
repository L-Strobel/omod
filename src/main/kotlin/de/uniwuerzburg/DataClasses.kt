package de.uniwuerzburg

import kotlinx.serialization.*
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope

@Serializable
data class ActivityData(val nodes: Map<Int, Node>)
@Serializable
data class Node(val leafs: Map<Int, Leaf>)
@Serializable
data class Leaf (
    val activityChains: List<String>,
    val stayTimeData: Map<String, List<List<Double>>>,
    val activityChainWeights: List<Double>
)

/**
 * Simplified landuse for work and home probabilities
 */
enum class Landuse {
    RESIDENTIAL, INDUSTRIAL, COMMERCIAL, OTHER;

    fun getWorkWeight() : Double {
        return when(this) {
            RESIDENTIAL -> 1.0
            INDUSTRIAL -> 2.0
            COMMERCIAL -> 2.0
            else -> 0.0
        }
    }

    companion object {
        fun getFromStr(str: String) : Landuse {
            return when(str) {
                "Residential" -> RESIDENTIAL
                "Industrial"  -> INDUSTRIAL
                "Commercial"  -> COMMERCIAL
                else -> OTHER
            }
        }
    }
}

/**
 * Model for a building
 *
 * @param coord coordinates in meters
 * @param area area of the building in meters
 * @param population population of building. Can be non-integer.
 * @param landuse OSM-Landuse of the building
 */
data class Building(
    val coord: Coordinate,
    val area: Double,
    val population: Double,
    val landuse: Landuse
)

data class MobiAgent (
    val id: Int,
    val home: Int,
    val work: Int
)

data class Cell (
    val population: Double,
    val priorWorkWeight: Double,
    val envelope: Envelope,
    val buildingIds: List<Int>,
    val featureCentroid: Coordinate
)