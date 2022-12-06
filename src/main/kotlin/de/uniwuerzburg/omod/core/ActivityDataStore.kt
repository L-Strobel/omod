package de.uniwuerzburg.omod.core

import kotlinx.serialization.Serializable

/**
 * Json format of data
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
 */
class ActivityDataStore(activityGroups: List<ActivityGroup>) {
    private val nodes: Map<Int, GroupData>
    private val thresh = 280 // Minimum number of samples that a valid activity chain distribution needs

    private val cHom = Weekday.values().size
    private val cMob = cHom * HomogeneousGrp.values().size
    private val cAge = cMob * MobilityGrp.values().size

    init {
        nodes = activityGroups.associateBy (
            { getKey(it.weekday, it.homogenousGroup, it.mobilityGroup, it.age) },
            { GroupData(it) }
        )
    }

    private fun getKey(wd: Weekday, homGrp: HomogeneousGrp, mobGrp: MobilityGrp, age: AgeGrp) : Int {
        return wd.ordinal + cHom * homGrp.ordinal + cMob * mobGrp.ordinal + cAge * age.ordinal
    }

    /**
     * Get the activity distributions for the specified day and agent.
     * Also ensures that the sample size is adequate (above 280 persons as recommended in the MID contract).
     * If not enough samples exist set the features to undefined in this order:
     * age -> mobility Group -> homogenous group -> weekday
     *
     * If givenChain != null the function will also ensure that there are parameters for the gaussian mixture
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

    fun getMixture(weekday: Weekday, homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp,
                    chain: List<ActivityType>) : Mixture {
        val from = chain.first()

        val groupData = searchFor(weekday, homogenousGroup, mobilityGroup, age) {
            it.chainsFrom[from]?.mixtures?.get(chain) != null
        }

        return groupData.chainsFrom[from]?.mixtures?.get(chain)!!
    }

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

    class GroupData(activityGroup: ActivityGroup) {
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
