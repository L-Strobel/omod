package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.io.json.readJsonFromResource
import de.uniwuerzburg.omod.utils.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.exp

/**
 * Creates agents by determining socio-demographic features as well as work and school locations.
 */
class DefaultAgentFactory (
    private val destinationFinder: DestinationFinder,
    private val popStrata: List<PopStratum>,
    private val dispatcher: CoroutineDispatcher
) : AgentFactory {
    private val strataDistr: DoubleArray = createCumDist(popStrata.map{it.stratumShare}.toDoubleArray())
    private val carOwnershipUtility: CarOwnershipUtility = readJsonFromResource("carOwnershipUtility.json")

    /**
     * Initialize population based on a share of the existing population.
     * Assigns socio-demographic features, and home, work, and school locations.
     *
     * @param share Share of the population to simulate
     * @param zones Possible home locations
     * @param populateBufferArea False: Only place agents in the focus area
     * @param rng Random number generator
     * @return Population of agents
     */
    override fun createAgents(
        share: Double, zones: List<AggLocation>, populateBufferArea: Boolean, rng: Random
    ): List<MobiAgent> {
        print("Creating Population...\r")
        // Real locations
        val grid = zones.filterIsInstance<Cell>()
        val buildings = if (populateBufferArea) {
            grid.flatMap { it.buildings }
        } else {
            grid.flatMap { it.buildings }.filter { it.inFocusArea }
        }

        // Determine populations of dummy zones
        val weightSumCells = destinationFinder.getWeightsNoOrigin(grid, ActivityType.HOME).sum()
        val totalPopCells = grid.sumOf { it.population }
        val homeWeightsZones = destinationFinder.getWeightsNoOrigin(zones, ActivityType.HOME)

        val dummyZones = mutableListOf<DummyLocation>()
        val dummyZonePopulation = mutableListOf<Int>()
        if (populateBufferArea) {
            for ((i, zone) in zones.withIndex()) {
                if (zone is DummyLocation) {
                    val hWeight = homeWeightsZones[i]
                    val synthPop = totalPopCells * (hWeight / weightSumCells)
                    dummyZones.add(zone)
                    dummyZonePopulation.add(synthPop.toInt())
                }
            }
        }

        // Determine number of agents
        val totalAgentsBuildings = (buildings.sumOf { it.population } * share).toInt()
        val totalAgentsDummy = (dummyZonePopulation.sum() * share).toInt()
        val totalNAgents = totalAgentsBuildings + totalAgentsDummy

        val homes = ArrayList<LocationOption>(totalNAgents)
        // Living in buildings
        val buildingPopDistr = createCumDistWOR(buildings.map { it.population.toInt() }.toIntArray())
        for (id in 0 until totalAgentsBuildings) {
            val i = sampleCumDistWOR(buildingPopDistr, rng)
            homes.add( buildings[i] )
        }
        // Living at dummy location
        val dummyPopDistr = createCumDistWOR(dummyZonePopulation.toIntArray())
        for (id in 0 until totalAgentsDummy) {
            val i = sampleCumDistWOR(dummyPopDistr, rng)
            homes.add( dummyZones[i] )
        }

        val agents = createAgentsFromHomes(homes, zones, rng)
        println("Creating Population...  Done!")
        return agents
    }

    /**
     * Initialize population with fixed number of agents.
     * Assigns socio-demographic features, and home, work, and school locations.
     *
     * @param nFocus number of agents in focus areas
     * @param zones Possible home locations
     * @param populateBufferArea False: Only place agents in the focus area
     * @param rng Random number generator
     * @return Population of agents
     */
    override fun createAgents(
        nFocus: Int, zones: List<AggLocation>, populateBufferArea: Boolean, rng: Random
    ): List<MobiAgent> {
        logger.info("Creating Population... ")

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
        val homes = ArrayList<LocationOption>(totalNAgents)
        for (id in 0 until totalNAgents) {
            // Get home zone (might be cell or dummy is node)
            val inside = id < nFocus // Should agent live inside focus area
            val homeCumDist = if (inside) insideCumDist else outsideCumDist
            val homeZoneID = sampleCumDist(homeCumDist, rng)
            val homeZone = zones[homeZoneID]

            // Get home location
            val home = if (homeZone is Cell) { // Home is building
                val buildingsHomeDist = createCumDist(
                    getHomeWeightsRestricted(homeZone.buildings, inside).toDoubleArray()
                )
                homeZone.buildings[sampleCumDist(buildingsHomeDist, rng)]
            } else { // IS dummy location
                homeZone
            }
            homes.add(home)
        }

        val agents = createAgentsFromHomes(homes, zones, rng)
        logger.info("Creating Population... Done!")
        return agents
    }

    /**
     * @param homes Home location
     * @param zones Options for work and school locations
     * @param rng Random number generator
     * @return Population of agents
     */
    private fun createAgentsFromHomes(
        homes: List<LocationOption>, zones: List<AggLocation>, rng: Random
    ) : List<MobiAgent> {
        val agents = ArrayList<MobiAgent>(homes.size)
        for (chunk in homes.withIndex().chunked(10_000)) {
            runBlocking(dispatcher) {
                val agentsFutures = mutableListOf<Deferred<MobiAgent>>()
                for ((id, home) in chunk) {
                    val coroutineRng = Random(rng.nextLong())
                    val agent = async {
                        createAgent(id, home, home.getAggLoc()!!, zones, coroutineRng)
                    }
                    agentsFutures.add(agent)
                }
                agents.addAll(agentsFutures.awaitAll())
            }
        }
        agents.shuffle(rng)
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
        val stratum = popStrata[sampleCumDist(strataDistr, rng)]
        val featureSet = stratum.sampleSocDemFeatures(rng)
        val ageGrp = AgeGrp.fromInt(featureSet.age)

        // Fixed locations
        val work = destinationFinder.getLocation(homeZone, zones, ActivityType.WORK, rng)
        val school = destinationFinder.getLocation(homeZone, zones, ActivityType.SCHOOL, rng)

        val ownsCar = if ((featureSet.age != null) and (featureSet.age!! < 17)) {
            false // You must be 17 to have a driver's license. Limit should be moved to config.
        } else {
            sampleOwnership( featureSet.hom, featureSet.mob, ageGrp, rng )
        }

        val agent = MobiAgent(
            id, featureSet.hom, featureSet.mob, featureSet.age, home, work, school, featureSet.sex, ownsCar
        )
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

    private fun sampleOwnership(
        homogenousGroup: HomogeneousGrp, mobilityGroup: MobilityGrp, age: AgeGrp, rng: Random
    ) : Boolean {
        val utility = carOwnershipUtility.calc( homogenousGroup, mobilityGroup, age )
        val p = 1 / (1 + exp(-utility))
        return rng.nextDouble() < p
    }
}