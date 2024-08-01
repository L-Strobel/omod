package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.utils.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.ArrayList

/**
 * Creates agents by determining socio-demographic features as well as work and school locations.
 */
class DefaultAgentFactory (
    private val destinationFinder: DestinationFinder,
    populationDef: PopulationDef,
    private val dispatcher: CoroutineDispatcher
) : AgentFactory {
    private val features: List<Triple<HomogeneousGrp, MobilityGrp, AgeGrp>>
    private val featureDistribution: DoubleArray

    init {
        val (f, fD) = getSocioDemographicFeatureDistr(populationDef)
        features = f
        featureDistribution = fD
    }

    /**
     * Create sociodemographic feature distribution form populationDef
     * @return Pair<Possible feature combinations, probability of the combination>
     */
    private fun getSocioDemographicFeatureDistr(populationDef: PopulationDef
    ): Pair<List<Triple<HomogeneousGrp, MobilityGrp, AgeGrp>>, DoubleArray> {
        val features = mutableListOf<Triple<HomogeneousGrp, MobilityGrp, AgeGrp>>()
        val jointProbability = mutableListOf<Double>()
        for ((hom, pHom) in populationDef.homogenousGroup) {
            for ((mob, pMob) in populationDef.mobilityGroup) {
                for ((age, pAge) in populationDef.age) {
                    features.add(Triple(hom, mob, age))
                    jointProbability.add(pHom*pMob*pAge)
                }
            }
        }
        return Pair(features, createCumDist(jointProbability.toDoubleArray()))
    }

    /**
     * Initialize population based on a share of the existing population.
     * Assigns socio-demographic features, and home, work, and school locations.
     *
     * TODO: Implement populateBufferArea for this function
     * TODO: Make parallel
     * // Idea create function getHomes() for both createAgents and then a unified parallel function for the rest
     * // Danger: Sample cumDistWOR is not threadsafe
     *
     * @param share Share of the population to simulate
     * @return Population of agents
     */
    override fun createAgents(share: Double, zones: List<AggLocation>, rng: Random): List<MobiAgent> {
        print("Creating Population...\r")
        // Real locations
        val grid = zones.filterIsInstance<Cell>()
        val buildings = grid.flatMap { it.buildings }

        // Determine populations of dummy zones
        val weightSumCells = destinationFinder.getWeightsNoOrigin(grid, ActivityType.HOME).sum()
        val totalPopCells = grid.sumOf { it.population }
        val homeWeightsZones = destinationFinder.getWeightsNoOrigin(zones, ActivityType.HOME)

        val dummyZones = mutableListOf<DummyLocation>()
        val dummyZonePopulation = mutableListOf<Int>()
        for ((i, zone) in zones.withIndex()) {
            if (zone is DummyLocation) {
                val hWeight = homeWeightsZones[i]
                val synthPop = totalPopCells * (hWeight / weightSumCells)
                dummyZones.add(zone)
                dummyZonePopulation.add(synthPop.toInt())
            }
        }

        // Determine number of agents
        val totalAgentsBuildings = (buildings.sumOf { it.population } * share).toInt()
        val totalAgentsDummy = (dummyZonePopulation.sum() * share).toInt()
        val totalNAgents = totalAgentsBuildings + totalAgentsDummy

        // Create agents
        val agents = ArrayList<MobiAgent>(totalNAgents)
        // Living in buildings
        val buildingPopDistr = createCumDistWOR(buildings.map { it.population.toInt() }.toIntArray())
        for (id in 0 until totalAgentsBuildings) {
            val i = sampleCumDistWOR(buildingPopDistr, rng)
            val home = buildings[i]
            agents.add(createAgent(id, home, home.cell!!, zones, rng))
        }
        // Living at dummy location
        val dummyPopDistr = createCumDistWOR(dummyZonePopulation.toIntArray())
        for (id in 0 until totalAgentsDummy) {
            val i = sampleCumDistWOR(dummyPopDistr, rng)
            val home = dummyZones[i]
            agents.add(createAgent(id + totalAgentsBuildings, home, home, zones, rng))
        }
        agents.shuffle(rng)
        println("Creating Population...  Done!")
        return agents
    }

    /**
     * Initialize population with fixed number of agents.
     * Assigns socio-demographic features, and home, work, and school locations.
     *
     * @param nFocus number of agents in focus areas
     * @return Population of agents
     */
    override fun createAgents(
        nFocus: Int, zones: List<AggLocation>, populateBufferArea: Boolean, rng: Random
    ): List<MobiAgent> {
        print("Creating Population...\r")

        // Home distributions inside and outside of focus area
        val insideHWeights = getHomeWeightsRestricted(zones, true).toDoubleArray()
        val outsideHWeight = getHomeWeightsRestricted(zones, false).toDoubleArray()
        val insideCumDist = createCumDist(insideHWeights)
        val outsideCumDist = createCumDist(outsideHWeight)

        val totalNAgents = if (populateBufferArea) {
            // Check the rough proportions of in and out of focus area homes for emergency break
            val hWeights = destinationFinder.getWeightsNoOrigin(zones, ActivityType.HOME)
            val inShare = insideHWeights.sum() / hWeights.sum()
            (nFocus / inShare).toInt()
        } else {
            nFocus
        }

        // Create agent until n life in the focus area
        val agents = ArrayList<MobiAgent>(totalNAgents)
        for (chunk in (0 until totalNAgents).chunked(10_000)) {
            runBlocking(dispatcher) {
                val agentsFutures = mutableListOf<Deferred<MobiAgent>>()
                for (id in chunk) {
                    val coroutineRng = Random(rng.nextLong())
                    val agent = async {
                        // Get home zone (might be cell or dummy is node)
                        val inside = id < nFocus // Should agent live inside focus area
                        val homeCumDist = if (inside) insideCumDist else outsideCumDist
                        val homeZoneID = sampleCumDist(homeCumDist, coroutineRng)
                        val homeZone = zones[homeZoneID]

                        // Get home location
                        val home = if (homeZone is Cell) { // Home is building
                            val buildingsHomeDist = createCumDist(
                                getHomeWeightsRestricted(homeZone.buildings, inside).toDoubleArray()
                            )
                            homeZone.buildings[sampleCumDist(buildingsHomeDist, coroutineRng)]
                        } else { // IS dummy location
                            homeZone
                        }
                        createAgent(id, home, homeZone, zones, coroutineRng)
                    }
                    agentsFutures.add(agent)
                }
                agents.addAll(agentsFutures.awaitAll())
            }
        }
        agents.shuffle(rng)
        println("Creating Population...  Done!")
        return agents
    }

    /**
     * Create agent with given home.
     *
     * @param home Home of the agent. Either a Building or a DummyLocation.
     * @param homeZone Routing cell of the home. Either a Cell or a DummyLocation
     * @return Agent
     */
    private fun createAgent(
        id: Int, home: LocationOption, homeZone: AggLocation, zones: List<AggLocation>, rng: Random
    ) : MobiAgent {
        // Sociodemographic features
        val agentFeatures = sampleCumDist(featureDistribution, rng)
        val homogenousGroup = features[agentFeatures].first
        val mobilityGroup = features[agentFeatures].second
        val age = features[agentFeatures].third

        // Fixed locations
        val work = destinationFinder.getLocation(homeZone, zones, ActivityType.WORK, rng)
        val school = destinationFinder.getLocation(homeZone, zones, ActivityType.SCHOOL, rng)

        val agent = MobiAgent(id, homogenousGroup, mobilityGroup, age, home, work, school)
        return agent
    }

    /**
     * Get the home weights of all destinations in the focus area and set the others to zero; or the other way around.
     *
     * @param destinations Destination options
     * @param inside True: Only the weights inside the focus area. False: Only those outside.
     * @return List of weights
     */
    private fun getHomeWeightsRestricted(destinations: List<LocationOption>, inside: Boolean) : List<Double> {
        val originalWeights = destinationFinder.getWeightsNoOrigin(destinations, ActivityType.HOME)
        return if (inside) {
            originalWeights.mapIndexed { i, weight -> destinations[i].inFocusArea.toDouble() * weight }
        } else {
            originalWeights.mapIndexed { i, weight -> (!destinations[i].inFocusArea).toDouble() * weight }
        }
    }
}