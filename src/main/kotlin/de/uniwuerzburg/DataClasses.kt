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
 * @param regionType RegioStar7 of the municipality
 */
data class Building(
    val coord: Coordinate,
    val area: Double,
    val population: Double,
    val landuse: Landuse,
    val regionType: Int
)

@Serializable
data class MobiAgent (
    val id: Int,
    val homogenousGroup: String,
    val mobilityGroup: String,
    val age: String,
    val home: Int,
    val work: Int,
    var profile: List<Activity>? = null
)

data class Cell (
    val population: Double,
    val priorWorkWeight: Double,
    val envelope: Envelope,
    val buildingIds: List<Int>,
    val featureCentroid: Coordinate,
    val regionType: Int
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
fun PopulationDef(map: Map<String, Map<String, Double>>): PopulationDef{
    require(map.containsKey("homogenousGroup"))
    require(map.containsKey("mobilityGroup"))
    require(map.containsKey("age"))

    return PopulationDef(map["homogenousGroup"]!!, map["mobilityGroup"]!!, map["age"]!!)
}
enum class ActivityType {
    HOME, WORK, BUSINESS, SCHOOL, SHOPPING, OTHER;
}

/**
 * Holds activity data.
 * Structure is designed for fast access. Where possible data is already processed.
 */
class ActivityDataMap(activityGroups: List<ActivityGroup>) {
    private val nodes: Map<Key, GroupData>

    init {
        nodes = activityGroups.associateBy (
            { Key(it.weekday, it.homogenousGroup, it.mobilityGroup, it.age) },
            { GroupData(it) }
        )
    }

    data class Key(
        private val weekday: String,
        private val homogenousGroup: String,
        private val mobilityGroup: String,
        private val age: String
    )

    /**
     * Get the activity distributions for the specified day and agent.
     * Also ensures that the sample size is adequate (above 280 persons as recommended in the MID contract).
     * If not enough samples exist set the features to undefined in this order:
     * age -> mobility Group -> homogenous group -> weekday
     *
     * If givenChain != null the function will also ensure that there are parameters for the gaussian mixture
     */
    fun get(weekday: String, homogenousGroup: String, mobilityGroup: String, age: String, from: ActivityType,
            givenChain: List<ActivityType>? = null): ChainData {
        require(from == ActivityType.HOME || from == ActivityType.OTHER )

        val key = Key(weekday, homogenousGroup, mobilityGroup, age)
        var groupData = nodes[key]

        // Check if data exists and sample size is adequate.
        val thresh = 280
        var check = groupData == null || groupData.sampleSize < thresh || groupData.chainsFrom[from] == null
        if (givenChain != null) {
            check = check || groupData?.chainsFrom?.get(from)?.mixtures?.get(givenChain) == null
        }
        if (check) {
            val keys = listOf(
                Key(weekday, homogenousGroup, mobilityGroup, "undefined"),
                Key(weekday, homogenousGroup, "undefined", "undefined"),
                Key(weekday, "undefined", "undefined", "undefined"),
                Key("undefined", "undefined", "undefined", "undefined")
            )

            for (k in keys) {
                groupData = nodes[k]
                check = groupData != null && groupData.sampleSize >= thresh && groupData.chainsFrom[from] != null
                if (givenChain != null) {
                    check = check && groupData?.chainsFrom?.get(from)?.mixtures?.get(givenChain) != null
                }
                if (check) {
                    break
                } else if (k == keys.last()) {
                    throw Exception("Couldn't find activity data for group: $key and chain $givenChain")
                }
            }
        }
        return groupData!!.chainsFrom[from]!!
    }

    class GroupData(activityGroup: ActivityGroup) {
        val sampleSize: Int
        val chainsFrom: Map<ActivityType, ChainData>

        init {
            sampleSize = activityGroup.sampleSize
            chainsFrom = mutableMapOf()
            chainsFrom[ActivityType.HOME] = ChainData(activityGroup.activityChains.filter { it.chain[0] == ActivityType.HOME })
            chainsFrom[ActivityType.OTHER] = ChainData(activityGroup.activityChains.filter { it.chain[0] == ActivityType.OTHER })
        }
    }

    class ChainData(activityChains: List<ActivityChain>) {
        val chains: List<List<ActivityType>>
        val distr: DoubleArray
        val mixtures: Map<List<ActivityType>, Mixture?>

        init {
            chains = activityChains.map { it.chain }
            distr = StochasticBundle.createCumDist(activityChains.map { it.weight }.toDoubleArray())
            mixtures = activityChains.associateBy(
                { i -> i.chain},
                { i ->
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
            )
        }
    }
    class Mixture(
        val distr: DoubleArray,
        val means: List<DoubleArray>,
        val covariances: List<Array<DoubleArray>>
    )
}