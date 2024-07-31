package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.io.json.ActivityChain
import de.uniwuerzburg.omod.io.json.ActivityGroup
import de.uniwuerzburg.omod.utils.createCumDist
import de.uniwuerzburg.omod.utils.sampleCumDist
import de.uniwuerzburg.omod.utils.sampleNDGaussian
import java.util.Random

/**
 * @param rng Random number generator
 * @param activityGroups A list of all possible activity groups
 */
class DefaultActivityGenerator (private val rng: Random, activityGroups: List<ActivityGroup>): ActivityGenerator {
    private val nodes: Map<Int, GroupData> // key exists for every combination of the features weekday, homogeneous group, mobility group, and age
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
     * Determine the activity chain of an agent.
     *
     * @param agent The agent
     * @param weekday The weekday
     * @param from The yesterday's last activity
     * @return List of activities conducted by the agent today
     */
    override fun getActivityChain(agent: MobiAgent, weekday: Weekday, from: ActivityType) : List<ActivityType> {
        val chainData = getChain(weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, from)
        val i = sampleCumDist(chainData.distr, rng)
        return chainData.chains[i]
    }

    /**
     * Get the stay times at each activity.
     *
     * @param activityChain The activities the agent will undertake that day
     * @param agent The agent
     * @param weekday The weekday
     * @return Stay times at each activity
     */
    override fun getStayTimes(activityChain: List<ActivityType>, agent: MobiAgent, weekday: Weekday
    ) : List<Double?> {
        return if (activityChain.size == 1) {
            // Stay at one location the entire day
            listOf(null)
        } else {
            // Sample stay times from gaussian mixture
            val mixture = getMixture(
                weekday, agent.homogenousGroup, agent.mobilityGroup, agent.age, activityChain
            )
            val i = sampleCumDist(mixture.distr, rng)
            val stayTimes = sampleNDGaussian(mixture.means[i], mixture.covariances[i], rng).toList()
            // Handle negative values. Last stay is always until the end of the day, marked by null
            stayTimes.map { if (it < 0 ) 0.0 else it } + null
        }
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
    private fun getChain(weekday: Weekday, homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp,
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
    private fun getMixture(weekday: Weekday, homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp,
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
        val sampleSize: Int = activityGroup.sampleSize
        val chainsFrom: Map<ActivityType, ChainData>

        init {
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
    private class ChainData(activityChains: List<ActivityChain>) {
        val chains: List<List<ActivityType>> = activityChains.map { it.chain }
        val distr: DoubleArray = createCumDist(activityChains.map { it.weight }.toDoubleArray())
        val mixtures: Map<List<ActivityType>, Mixture?> = activityChains.associateBy(
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

    /**
     * Gaussian mixture
     */
    private class Mixture(
        val distr: DoubleArray,
        val means: List<DoubleArray>,
        val covariances: List<Array<DoubleArray>>
    )
}