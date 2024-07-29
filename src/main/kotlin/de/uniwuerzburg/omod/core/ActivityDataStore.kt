package de.uniwuerzburg.omod.core

import kotlinx.serialization.Serializable

/**
 * Json storage format.
 * For activity chain probability distribution container.
 */
@Serializable
data class ActivityGroup(
    val weekday: Weekday,
    val homogenousGroup: HomogeneousGrp,
    val mobilityGroup: MobilityGrp,
    val age: AgeGrp,
    val sampleSize: Int,
    val activityChains: List<ActivityChain>
)
/**
 * Json storage format.
 * For activity chain.
 * Contains the corresponding weight in the probability distribution and
 * the Gaussian Mixture of the dwell time distribution.
 */
@Serializable
data class ActivityChain(
    val chain: List<ActivityType>,
    val weight: Double,
    val gaussianMixture: GaussianMixture?
)
/**
 * Json storage format.
 * Gaussian Mixture of dwell time distribution.
 */
@Serializable
class GaussianMixture(
    val weights: List<Double>,
    val means: List<List<Double>>,
    val covariances: List<List<List<Double>>>
)

/**
 * Data structure for the activity chain probability distributions.
 * Structure is designed for fast access.
 *
 * For every combination of the features weekday, homogeneous group, mobility group, and age
 * *ActivityDataStore* contains on element of *ActivityGroup*.
 *
 * @param activityGroups A list of all possible activity groups
 */
class ActivityDataStore(activityGroups: List<ActivityGroup>) {
    private val nodes: Map<Int, GroupData>
    private val thresh = 30 // Minimum number of samples that a valid activity chain distribution needs

    private val cHom = Weekday.entries.size
    private val cMob = cHom * HomogeneousGrp.entries.size
    private val cAge = cMob * MobilityGrp.entries.size

    init {
        nodes = activityGroups.associateBy (
            { getKey(it.weekday, it.homogenousGroup, it.mobilityGroup, it.age) },
            { GroupData(it) }
        )
    }

    /**
     * Get hash key based on a combination of features
     */
    private fun getKey(wd: Weekday, homGrp: HomogeneousGrp, mobGrp: MobilityGrp, age: AgeGrp) : Int {
        return wd.ordinal + cHom * homGrp.ordinal + cMob * mobGrp.ordinal + cAge * age.ordinal
    }

    /**
     * Get the probability distributions for the specified combination of features.
     *
     * A probability distributions must have at least *thresh* samples, if this is not the case the features are set
     * to *UNDEFINED* in this order:
     *
     * age -> mobility group -> homogeneous group -> weekday
     *
     * @param weekday weekday
     * @param homogenousGroup homogeneous group of the agent
     * @param mobilityGroup mobility group of the agent
     * @param age age group of the agent
     * @param from the first activity in the chain
     *
     * @return probability distribution over possible activity chains
     */
    fun getChain(weekday: Weekday, homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp,
                 from: ActivityType): ChainData {
        require(from == ActivityType.HOME || from == ActivityType.OTHER) {
            "Chain starts at $from. This is not allowed."
        }

        val groupData = searchFor(weekday, homogenousGroup, mobilityGroup, age) {
            (it.sampleSize >= thresh) && (it.chainsFrom[from] != null)
        }

        return groupData.chainsFrom[from]!!
    }

    /**
     * Get the gaussian mixture for a combination of features and a given activity chain.
     *
     * A mixture must have at least *thresh* samples, if this is not the case the features are set
     * to *UNDEFINED* in this order:
     *
     * age -> mobility group -> homogeneous group -> weekday
     *
     * @param weekday weekday
     * @param homogenousGroup homogeneous group of the agent
     * @param mobilityGroup mobility group of the agent
     * @param age age group of the agent
     * @param chain activity chain
     *
     * @return gaussian mixture of the dwell times
     */
    fun getMixture(weekday: Weekday, homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp,
                    chain: List<ActivityType>) : Mixture {
        val from = chain.first()

        val groupData = searchFor(weekday, homogenousGroup, mobilityGroup, age) {
            it.chainsFrom[from]?.mixtures?.get(chain) != null
        }

        return groupData.chainsFrom[from]?.mixtures?.get(chain)!!
    }

    /**
     * Search for the data with the given combination of features.
     */
    private fun searchFor(weekday: Weekday, homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp,
                          predicate: (GroupData) -> Boolean) : GroupData {
        // Relax conditions of probability distribution in this order
        val keys = listOf(
            getKey(weekday, homogenousGroup, mobilityGroup, age),
            getKey(weekday, homogenousGroup, mobilityGroup, AgeGrp.UNDEFINED),
            getKey(weekday, homogenousGroup, MobilityGrp.UNDEFINED,  AgeGrp.UNDEFINED),
            getKey(weekday, HomogeneousGrp.UNDEFINED, MobilityGrp.UNDEFINED,  AgeGrp.UNDEFINED),
            getKey(Weekday.UNDEFINED, HomogeneousGrp.UNDEFINED, MobilityGrp.UNDEFINED,  AgeGrp.UNDEFINED)
        )
        for (k in keys) {
            val groupData = nodes[k]
            if ( (groupData != null) && predicate(groupData) ) {
                return groupData
            }
        }
        throw Exception("Couldn't find desired data for group: $weekday, $homogenousGroup, $mobilityGroup, $age")
    }

    /**
     * Internal data container
     */
    private class GroupData(activityGroup: ActivityGroup) {
        val sampleSize: Int
        val chainsFrom: Map<ActivityType, ChainData>

        init {
            sampleSize = activityGroup.sampleSize
            chainsFrom = mutableMapOf()
            chainsFrom[ActivityType.HOME] = ChainData(
                activityGroup.activityChains.filter { it.chain[0] == ActivityType.HOME }
            )
            chainsFrom[ActivityType.OTHER] = ChainData(
                activityGroup.activityChains.filter { it.chain[0] == ActivityType.OTHER }
            )
        }
    }

    /**
     * Output format of the probability distribution
     *
     * @param activityChains list of all possible activity chains
     */
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

    /**
     * Gaussian mixture
     */
    class Mixture(
        val distr: DoubleArray,
        val means: List<DoubleArray>,
        val covariances: List<Array<DoubleArray>>
    )
}
