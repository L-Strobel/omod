package de.uniwuerzburg.omod.core

import kotlinx.serialization.Serializable

/**
 * Json format of data
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

/**
 * Holds activity data.
 * Structure is designed for fast access. Where possible data is already processed.
 *
 * TODO: Speed up tests
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
        require(from == ActivityType.HOME || from == ActivityType.OTHER) { "Chain starts at $from. This is not allowed."}

        val key = Key(weekday, homogenousGroup, mobilityGroup, age)
        var groupData = nodes[key]

        // Check if data exists and sample size is adequate.
        val thresh = 280
        var check = (groupData == null) || (groupData.sampleSize < thresh) || (groupData.chainsFrom[from] == null)
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
                check = (groupData != null) && (groupData.sampleSize >= thresh) && (groupData.chainsFrom[from] != null)
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
            distr = createCumDist(activityChains.map { it.weight }.toDoubleArray())
            mixtures = activityChains.associateBy(
                { i -> i.chain},
                { i ->
                    val g = i.gaussianMixture
                    if (g != null) {
                        Mixture(
                            createCumDist(g.weights.toDoubleArray()),
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