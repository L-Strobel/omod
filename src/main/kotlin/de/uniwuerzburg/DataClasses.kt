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
    val gaussianMixture: GaussianMixture?
)
@Serializable
class GaussianMixture(
    val weights: List<Double>,
    val means: List<List<Double>>,
    val covariances: List<List<List<Double>>>
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

/**
 * Holds activity data.
 * Structure is designed for fast access. Where possible data is already processed.
 */
class ActivityDataMap(activityGroups: List<ActivityGroup>) {
    private val nodes: Map<Key, Data>

    init {
        nodes = activityGroups.associateBy (
            { Key(it.weekday, it.homogenousGroup, it.mobilityGroup, it.age) },
            { Data(it) }
        )
    }

    data class Key(
        private val weekday: String,
        private val homogenousGroup: String,
        private val mobilityGroup: String,
        private val age: String
    )

    fun get(weekday: String, homogenousGroup: String, mobilityGroup: String, age: String): Data? {
        return nodes[Key(weekday, homogenousGroup, mobilityGroup, age)]
    }

    class Data(activityGroup: ActivityGroup) {
        val sampleSize: Int
        val chains: List<List<ActivityType>>
        val distr: DoubleArray
        val mixtures: List<Mixture?>

        init {
            sampleSize = activityGroup.sampleSize
            chains = activityGroup.activityChains.map { it.chain }
            distr = StochasticBundle.createCumDist(activityGroup.activityChains.map { it.weight }.toDoubleArray())
            mixtures = activityGroup.activityChains.map { i ->
                val g = i.gaussianMixture
                if (g != null) {
                    Mixture(
                        StochasticBundle.createCumDist(g.weights.toDoubleArray()),
                        g.means.map { j -> j.toDoubleArray() },
                        g.covariances.map { j ->  (j.map { k -> k.toDoubleArray() }).toTypedArray()}
                    )
                } else {
                    null
                }
            }
        }

        class Mixture(
            val distr: DoubleArray,
            val means: List<DoubleArray>,
            val covariances: List<Array<DoubleArray>>
        )
    }
}