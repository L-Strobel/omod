package de.uniwuerzburg

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonTransformingSerializer
import org.apache.commons.math3.analysis.function.Gaussian
import org.apache.commons.math3.stat.correlation.Covariance
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import java.beans.Encoder

/**
 * Data format of behavior data extracted from MID 2017
 */
@Serializable
data class ActivityGroup(
    val weekday: String,
    val homogenousGroup: String,
    val mobilityGroup: String,
    val age: String,
    val sampleSize: Int,
    val activityChains: List<ActivityChain>
)
@Serializable
data class ActivityChain(
    val chain: List<ActivityType>,
    val weight: Double,
    val gaussianMixture: List<GaussianMixture>
)
@Serializable
class GaussianMixture(
    val weight: DoubleArray,
    val mean: Array<DoubleArray>,
    val covariance: Array<Array<DoubleArray>>
)
@Serializable
data class DistanceDistributions(
    val home_work: Map<Int, Distribution>,
    val home_school: Map<Int, Distribution>,
    val any_shopping: Map<Int, Distribution>,
    val any_other: Map<Int, Distribution>
) {
    @Serializable
    data class Distribution(
        val distribution: String,
        val shape: Double,
        val scale: Double
    )
}
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

@Serializable
data class MobiAgent (
    val id: Int,
    val home: Int,
    val work: Int,
    var profile: List<Activity>? = null
)

data class Cell (
    val population: Double,
    val priorWorkWeight: Double,
    val envelope: Envelope,
    val buildingIds: List<Int>,
    val featureCentroid: Coordinate
)

@Serializable
data class Activity (
    val type: ActivityType,
    val stayTime: Double,
    val x: Double,
    val y: Double
)
@Serializable
data class PopulationDef (
    val homogenousGroup: Map<String, Double>,
    val mobilityGroup: Map<String, Double>,
    val age: Map<String, Double>
)
enum class ActivityType {
    HOME, WORK, BUSINESS, SCHOOL, SHOPPING, OTHER;
}