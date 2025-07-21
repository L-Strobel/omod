package de.uniwuerzburg.omod.core

import com.graphhopper.GraphHopper
import com.graphhopper.gtfs.GraphHopperGtfs
import com.graphhopper.gtfs.PtRouter
import de.uniwuerzburg.omod.core.models.ModeChoiceOption
import de.uniwuerzburg.omod.core.models.*
import de.uniwuerzburg.omod.io.geojson.*
import de.uniwuerzburg.omod.io.gtfs.clipGTFSFile
import de.uniwuerzburg.omod.io.gtfs.getPublicTransitSimDays
import de.uniwuerzburg.omod.io.json.*
import de.uniwuerzburg.omod.io.osm.BuildingData
import de.uniwuerzburg.omod.io.osm.readOSM
import de.uniwuerzburg.omod.io.overture.readOverture
import de.uniwuerzburg.omod.io.readCensus
import de.uniwuerzburg.omod.routing.RoutingCache
import de.uniwuerzburg.omod.routing.RoutingMode
import de.uniwuerzburg.omod.routing.createGraphHopper
import de.uniwuerzburg.omod.routing.createGraphHopperGTFS
import de.uniwuerzburg.omod.utils.CRSTransformer
import de.uniwuerzburg.omod.utils.ProgressBar
import de.uniwuerzburg.omod.utils.fastCovers
import kotlinx.coroutines.*
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.kdtree.KdNode
import org.locationtech.jts.index.kdtree.KdTree
import us.dustinj.timezonemap.TimeZoneMap
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.time.TimeSource

/**
 * Open-Street-Maps MObility Demand generator (OMOD)
 * Creates daily activity schedules in the form of activity chains and dwell times.
 *
 * @param areaFile GeoJSON of the focus area
 * @param osmFile osm.pbf file that covers at least the focus area and buffer area
 * @param routingMode Method of distance calculation used in the simulation
 * @param cache Option to cache the routing matrix for subsequent runs
 * @param cacheDir Directory where the cache should be located
 * @param odFile Origin-Destination Matrix in GeoJSON format. Optional input used for calibration.
 * @param gridPrecision Precision of the routing grid
 * @param seed Random number seed
 * @param bufferRadius Distance with which the focus area is buffered to obtain the buffer area
 * @param censusFile GeoJSON containing census information (Population distribution in the model area)
 * @param populateBufferArea Option to populate the buffer area with agents
 * @param distanceCacheSize Maximum size of the routing matrix cache
 * @param populationFile File that defines the distribution of socio-demographic features for the agent population
 * @param activityGroupFile File that defines the activity chains and their dwell times for the chosen location
 * @param nWorker Number of parallel coroutines that can be executed at the same time.
 * NULL = Number of CPU-Cores available.
 * @param gtfsFile GTFS location (Directory or .zip file)
 */
class Omod(
    areaFile: File,
    private val osmFile: File,
    routingMode: RoutingMode = RoutingMode.BEELINE,
    cache: Boolean = true,
    private val cacheDir: Path = Paths.get("omod_cache/"),
    odFile: File? = null,
    gridPrecision: Double = 200.0,
    seed: Long? = null,
    bufferRadius: Double = 0.0,
    censusFile: File? = null,
    private val populateBufferArea: Boolean = true,
    val distanceCacheSize: Long = 400e6.toLong(),
    populationFile: File? = null,
    activityGroupFile: File? = null,
    nWorker: Int? = null,
    private val gtfsFile: File? = null,
    overtureRelease: String? = null,
    carOwnershipOption: CarOwnershipOption = CarOwnershipOption.FIX,
    private val modeSpeedUp: Map<Mode, Double> = mapOf()
) {
    @Suppress("MemberVisibilityCanBePrivate")
    val kdTree: KdTree
    @Suppress("MemberVisibilityCanBePrivate")
    val buildings: List<Building>
    private var hopper: GraphHopper?
    private val grid: List<Cell>
    private val zones: List<AggLocation> // Grid + DummyLocations for commuting locations
    private val activityGenerator: ActivityGenerator
    private val mainRng: Random = if (seed != null) Random(seed) else Random()
    val transformer: CRSTransformer
    private val routingCache: RoutingCache
    private var censusAvailable = false
    private val dispatcher = if (nWorker != null) Dispatchers.Default.limitedParallelism(nWorker) else Dispatchers.Default
    private val destinationFinder: DestinationFinder
    private val agentFactory: AgentFactory
    private var gtfsComponents: GTFSComponents? = null
    private val focusArea: Geometry
    private val fullArea: Geometry
    init {
        val timeSource = TimeSource.Monotonic
        val timestampStartInit = timeSource.markNow()

        // Load population distribution
        val popStrata: List<PopStratum> = if (populationFile != null) {
            readJson(populationFile)
        } else {
            readJsonFromResource("Population.json")
        }

        // Load activity chain data
        val activityGroups: List<ActivityGroup> = if (activityGroupFile !=null){
            readJson(activityGroupFile)
        } else {
            readJsonFromResource("ActivityGroups.json")
        }

        // Load distance distributions
        val mutLocChoiceFuns: MutableMap<ActivityType, LocationChoiceDCWeightFun> =
            readJsonFromResource("LocChoiceWeightFuns.json")
        if (censusFile != null) {
            censusAvailable = true
            mutLocChoiceFuns[ActivityType.HOME] = ByPopulation
        }
        mutLocChoiceFuns[ActivityType.BUSINESS] = mutLocChoiceFuns[ActivityType.OTHER]!!
        val locChoiceWeightFuns = mutLocChoiceFuns.toMap()

        // Geometry Factory
        val geometryFactory = GeometryFactory()

        // Load focus area
        focusArea = readGeoJsonGeom(areaFile, geometryFactory).union()

        // Get CRSTransformer
        val center = focusArea.centroid
        transformer = CRSTransformer( center.coordinate.y )

        // Add buffer to area
        val utmFocusArea = transformer.toModelCRS(focusArea)
        val utmArea = utmFocusArea.buffer(bufferRadius).convexHull()
        fullArea = transformer.toLatLon(utmArea)

        // Get map data
        val mapDataSource = if (overtureRelease != null) MapDataSource.OVERTURE else MapDataSource.OSM
        buildings = getBuildings(
            focusArea, fullArea, osmFile, bufferRadius,  transformer,
            geometryFactory, censusFile, cacheDir, cache, locChoiceWeightFuns, mapDataSource, nWorker
        )

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEach { building ->
            kdTree.insert(building.coord, building)
        }

        // Create grid (used for speed up)
        grid = makeClusterGrid(gridPrecision, buildings, geometryFactory, transformer)
        for (cell in grid) {
            cell.buildings.forEach { it.cell = cell }
        }

        // Create graphhopper
        hopper = if (routingMode == RoutingMode.GRAPHHOPPER) {
            createGraphHopper(
                osmFile.toString(),
                Paths.get(cacheDir.toString(), "routing-graph-cache", osmFile.name).toString()
            )
        } else {
            null
        }

        // Create routing cache
        routingCache = RoutingCache(routingMode, hopper, distanceCacheSize, dispatcher)
        if (routingMode == RoutingMode.GRAPHHOPPER) {
            val weightFunction = locChoiceWeightFuns[ ActivityType.OTHER]!!
            val priorityValues = grid.map { weightFunction.calcForNoOrigin(it) } // Priority of cells for caching
            routingCache.load(grid, cacheDir, priorityValues)
        }

        // Activity generator
        activityGenerator = ActivityGeneratorDefault(activityGroups)

        // Destination finder
        destinationFinder = DestinationFinderDefault(routingCache, locChoiceWeightFuns)

        // Calibration
        if (odFile != null) {
            logger.info("Calibrating with OD-Matrix...")
            // Read OD-Matrix that is used for calibration
            val odZones = ODZone.readODMatrix(odFile, geometryFactory, transformer)
            // Add TAZ to buildings and cells
            val dummyZones = addODZoneInfo(odZones, geometryFactory, transformer)
            zones = grid + dummyZones
            // Get calibration factors based on OD-Matrix
            destinationFinder.calibrate(zones, odZones)
            logger.info("Calibration done!")
        } else {
            zones = grid
        }

        // Car Ownership
        val carOwnership = when (carOwnershipOption) {
            CarOwnershipOption.FIX -> {
                CarOwnershipFixedProbability(17)
            }
            CarOwnershipOption.MNL -> {
                val carOwnershipUtility: CarOwnershipUtility = readJsonFromResource("carOwnershipUtility.json")
                CarOwnershipMNL(carOwnershipUtility, 17)
            }
        }

        // Agent factory
        agentFactory = AgentFactoryDefault(destinationFinder, carOwnership, popStrata, dispatcher)

        logger.info("Initializing OMOD took: ${timeSource.markNow() - timestampStartInit}")
    }

    // Factories
    companion object {
        /**
         * Create OMOD object with default parameters
         *
         * @param areaFile GeoJSON of the focus area
         * @param osmFile osm.pbf file that covers at least the focus area and buffer area
         */
        @Suppress("unused")
        fun defaultFactory(areaFile: File, osmFile: File): Omod {
            return Omod(areaFile, osmFile)
        }
    }

    /**
     * Gather and combine all necessary information about the model area.
     * First checks if the data is cached and calls getBuildings().
     * Caches the data if it wasn't already.
     *
     * @param focusArea Focus area in latitude longitude crs
     * @param fullArea Buffered area in latitude longitude crs
     * @param osmFile osm.pbf file that covers the model area
     * @param bufferRadius Distance that the focus area will be buffered with
     * @param transformer Used for CRS conversions
     * @param geometryFactory Geometry factory
     * @param censusFile Distribution of the population in the area
     * @param cacheDir Cache directory
     * @param cache IF false this function will always load the data anew.
     * @return List of buildings with all necessary features
     */
    private fun getBuildings(focusArea: Geometry, fullArea: Geometry,
                             osmFile: File, bufferRadius: Double = 0.0,
                             transformer: CRSTransformer, geometryFactory: GeometryFactory,
                             censusFile: File?, cacheDir: Path, cache: Boolean,
                             locChoiceWeightFuns: Map<ActivityType, LocationChoiceDCWeightFun>,
                             mapType: MapDataSource = MapDataSource.OSM, nWorker:Int?
    ) : List<Building> {
        // Is cached?
        val bound = focusArea.envelopeInternal
        val cachePath = Paths.get(cacheDir.toString(),
            "AreaBounds${listOf(bound.minX, bound.maxX, bound.minY, bound.maxY)
                .toString().replace(" ", "")}" +
                    "Buffer${bufferRadius}" +
                    "Census${censusFile?.nameWithoutExtension ?: false}" +
                    ".geojson"
        )

        // Check cache
        val collection: GeoJsonFeatureCollection =  if (cache and cachePath.toFile().exists()) {
            readJsonStream(cachePath)
        } else {
            // Load data
            var buildings: List<BuildingData> = when(mapType) {
                MapDataSource.OVERTURE -> readOverture(focusArea, fullArea, geometryFactory, transformer,nWorker)
                MapDataSource.OSM -> readOSM(focusArea, fullArea, osmFile, geometryFactory, transformer)
            }

            // Add census data if available
            if (censusFile != null) {
                buildings = readCensus(buildings, transformer, geometryFactory, censusFile, mainRng)
            }

            // Convert to GeoJSON
            val collection = GeoJsonFeatureCollection(
                features = buildings.map {
                    val center = it.geometry.centroid
                    val coords = transformer.toLatLon(center).coordinate
                    val geometry = GeoJsonPoint(listOf(coords.y, coords.x))

                    val properties = GeoJsonBuildingProperties(
                        osm_id = it.osm_id,
                        in_focus_area = it.inFocusArea,
                        area = it.area,
                        population = it.population,
                        landuse = it.landuse,
                        number_shops = it.nShops,
                        number_offices = it.nOffices,
                        number_schools = it.nSchools,
                        number_universities = it.nUnis,
                        number_place_of_worship = it.nPlaceOfWorship,
                        number_cafe = it.nCafe,
                        number_fast_food = it.nFastFood,
                        number_kindergarten = it.nKinderGarten,
                        number_tourism = it.nTourism,
                    )
                    GeoJsonFeature(geometry = geometry, properties = properties)
                }
            )

            // Put into cache
            if (cache) {
                Files.createDirectories(cachePath.parent)
                writeJsonStream(collection, cachePath)
            }
            collection
        }
        return Building.fromGeoJson(collection, geometryFactory, transformer, locChoiceWeightFuns)
    }

    /**
     * Determine the OD-Zone of each building and cell. Creates dummy locations if necessary.
     */
    private fun addODZoneInfo(
        odZones: List<ODZone>, geometryFactory: GeometryFactory, transformer: CRSTransformer
    ) : List<DummyLocation> {
        val dummyZones = mutableListOf<DummyLocation>()

        // Get buildings in every TAZ and add the TAZ to that building
        for (odZone in odZones) {
            val zoneBuildings = mutableListOf<Building>()

            fastCovers(odZone.geometry, listOf(10000.0, 5000.0, 1000.0), geometryFactory,
                ifNot = { },
                ifDoes = { e ->
                    zoneBuildings.addAll(kdTree.query(e).map { ((it as KdNode).data as Building) })
                },
                ifUnsure = { e ->
                    zoneBuildings.addAll(kdTree.query(e).map { ((it as KdNode).data as Building) }
                        .filter { odZone.geometry.contains(it.point) })
                }
            )

            val activities = setOf(odZone.originActivity, odZone.destinationActivity)

            if (zoneBuildings.isEmpty()) {
                val centroid =  odZone.geometry.centroid
                val latlonCoord = transformer.toLatLon( centroid ).coordinate
                // Create dummy node for TAZ
                val dummyLoc = DummyLocation(
                    coord = centroid.coordinate,
                    latlonCoord = latlonCoord,
                    odZone = odZone,
                    transferActivities = activities
                )
                dummyZones.add(dummyLoc)
                odZone.aggLocs.add(dummyLoc)
            } else {
                for (building in zoneBuildings) {
                    // Remember ODZone
                    building.odZone = odZone
                }
                // Is zone in focus area?
                odZone.inFocusArea = zoneBuildings.any{ it.inFocusArea }
            }
        }

        // Add OD-Zones to cells and vice versa
        for (cell in grid) {
            // OD-Zone most buildings in cell belong to
            val odZone = cell.buildings.groupingBy { it.odZone }.eachCount().maxByOrNull { it.value }!!.key

            cell.odZone = odZone
            odZone?.aggLocs?.add(cell)
        }

        return dummyZones
    }

    /**
     * Get the activity locations for the given agent.
     *
     * @param agent The agent
     * @param activityChain The activities the agent will undertake that day
     * @param start Location the day starts at
     * @return Locations of each activity
     */
    private fun getLocations(
        agent: MobiAgent, activityChain: List<ActivityType>,
        start: LocationOption, rng: Random
    ) : List<LocationOption> {
        val locations = mutableListOf<LocationOption>()
        locations.add(start)
        for (i in 1 until activityChain.size) {
            val location =
                when (activityChain[i]) {
                    ActivityType.HOME -> agent.home
                    ActivityType.WORK -> agent.work
                    ActivityType.SCHOOL -> agent.school
                    ActivityType.SHOPPING -> {
                        destinationFinder.getLocation(locations[i-1].getAggLoc()!!, zones, ActivityType.SHOPPING, rng)
                    }
                    else -> {
                        destinationFinder.getLocation(locations[i-1].getAggLoc()!!, zones, ActivityType.OTHER, rng)
                    }
                }
            locations.add(location)
        }
        return locations
    }

    /**
     * Get the activity schedule (Activity types, locations, and stay times)
     * Defines the start location based on the last activity yesterday.
     *
     * @param agent The agent
     * @param weekday The weekday
     * @param from Last activity yesterday
     * @return Activity schedule
     */
    private fun getActivitySchedule(
        agent: MobiAgent, rng: Random, weekday: Weekday = Weekday.UNDEFINED,
        from: ActivityType = ActivityType.HOME,
    ): List<Activity> {
        val location = when(from) {
            ActivityType.HOME -> agent.home
            ActivityType.WORK -> agent.work
            ActivityType.SCHOOL -> agent.school
            else -> throw Exception("Start must be either Home, Work, School, or coordinates must be given. Agent: ${agent.id}")
        }
        return getActivitySchedule(agent, rng, weekday, from, location, )
    }

    /**
     * Get the activity schedule (Activity types, locations, and stay times)
     *
     * @param agent The agent
     * @param weekday The weekday
     * @param from Last activity yesterday
     * @param start Location the day starts at
     * @return Activity schedule
     */
    private fun getActivitySchedule(
        agent: MobiAgent,  rng: Random, weekday: Weekday = Weekday.UNDEFINED,
        from: ActivityType = ActivityType.HOME, start: LocationOption
    ): List<Activity> {
        val activityChain = activityGenerator.getActivityChain(agent, weekday, from, rng)
        val stayTimes = activityGenerator.getStayTimes(activityChain, agent, weekday, rng)
        val locations = getLocations(agent, activityChain, start, rng)
        return List(activityChain.size) { i ->
            Activity(activityChain[i], stayTimes[i], locations[i])
        }
    }

    /**
     * Generate the mobility demand of the area.
     *
     * @param n_agents Number of agents
     * @param start_wd Weekday of the first simulated day
     * @param n_days Number of consecutive days to simulate
     * @return List of agents each with an activity schedules for every simulated day
     */
    fun run(n_agents: Int, start_wd: Weekday = Weekday.UNDEFINED, n_days: Int = 1) : List<MobiAgent> {
        val agents = agentFactory.createAgents(n_agents, zones, populateBufferArea, mainRng)
        return run(agents, start_wd, n_days)
    }

    /**
     * Generate the mobility demand of the area.
     *
     * @param shareOfPop Share of population to simulate
     * @param start_wd Weekday of the first simulated day
     * @param n_days Number of consecutive days to simulate
     * @return List of agents each with an activity schedules for every simulated day
     */
    fun run(shareOfPop: Double, start_wd: Weekday = Weekday.UNDEFINED, n_days: Int = 1) : List<MobiAgent> {
        if (!censusAvailable) {
            throw Exception(
                "Agent population is supposed to be based on the population but no census file is provided." +
                        "Consider adding a census file with --census or use --n_agents instead.")
        }

        val agents = agentFactory.createAgents(shareOfPop, zones, populateBufferArea, mainRng)
        return run(agents, start_wd, n_days)
    }

    /**
     * Generate the mobility demand of the area.
     *
     * @param agents Agents
     * @param start_wd Weekday of the first simulated day
     * @param n_days Number of consecutive days to simulate
     * @return List of agents each with an activity schedules for every simulated day
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun run(agents: List<MobiAgent>, start_wd: Weekday = Weekday.UNDEFINED, n_days: Int = 1) : List<MobiAgent> {
        val timeSource = TimeSource.Monotonic
        val timestampStartInit = timeSource.markNow()
        val jobsDone = AtomicInteger()
        val totalJobs = (agents.size).toDouble()

        for (chunk in agents.chunked(AppConstants.nAllowedCoroutines)) { // Don't launch to many coroutines at once
            runBlocking(dispatcher) {
                for (agent in chunk) {
                    val coroutineRng = Random(mainRng.nextLong())
                    launch(dispatcher) {
                        runAgent(agent, start_wd, n_days, coroutineRng)
                        val done = jobsDone.incrementAndGet()
                        print("Activity generation: ${ProgressBar.show(done / totalJobs)}\r")
                    }
                }
            }
        }
        println("Activity generation: " + ProgressBar.done())
        routingCache.toOOMCache() // Save routing cache
        logger.info("Activity generation took: ${timeSource.markNow() - timestampStartInit}")
        return agents
    }

    /**
     * Generate the mobility demand for one agent
     *
     * @param agent
     * @param start_wd Weekday of the first simulated day
     * @param n_days Number of consecutive days to simulate
     * @return agent
     */
    private fun runAgent(
        agent: MobiAgent, start_wd: Weekday = Weekday.UNDEFINED, n_days: Int = 1,
        rng: Random
    ) : MobiAgent {
        var weekday = start_wd
        for (i in 0 until n_days) {
            val activities = if (agent.mobilityDemand.isEmpty()) {
                getActivitySchedule(agent, rng,  weekday)
            } else {
                val lastActivity = agent.mobilityDemand.last().activities.last()
                getActivitySchedule(
                    agent,
                    rng,
                    weekday,
                    from = lastActivity.type,
                    start = lastActivity.location,

                )
            }
            agent.mobilityDemand.add( Diary(i, weekday, activities) )
            weekday = weekday.next()
        }
        return agent
    }

    /**
     * Determine the mode of each trip and calculate the distance and time.
     *
     * @param agents Agents with trips (usually the trips have an UNDEFINED mode at this point)
     * @param modeChoiceOption Mode choice strategy.
     * @param withPath Return the lat-lon coordinates of the car trips.
     * @return agents. Now their trips have specified modes.
     */
    fun doModeChoice(
        agents: List<MobiAgent>, modeChoiceOption: ModeChoiceOption, withPath: Boolean
    ) : List<MobiAgent> {
        when (modeChoiceOption) {
            ModeChoiceOption.NONE -> { return agents } // Do nothing
            ModeChoiceOption.CAR_ONLY -> {
                setupHopper()
                val modeChoice = ModeChoiceCarOnly(hopper!!, withPath)
                modeChoice.doModeChoice(agents, mainRng, dispatcher, modeSpeedUp)
                return agents
            }
            ModeChoiceOption.GTFS -> {
                setupHopper()
                setupGTFS()
                try {
                    val modeChoice = ModeChoiceGTFS(
                        hopper!!, gtfsComponents!!.ptRouter,
                        gtfsComponents!!.ptSimDays, gtfsComponents!!.timeZone,
                        withPath
                    )
                    modeChoice.doModeChoice(agents, mainRng, dispatcher, modeSpeedUp)
                    return agents
                } finally {
                    gtfsComponents!!.gtfsHopper.close()
                }
            }
        }
    }

    /**
     * Init GraphHopper.
     */
    private fun setupHopper() {
        // Get a GraphHopper if none exists
        if (hopper == null) {
            hopper = createGraphHopper(
                osmFile.toString(),
                Paths.get(cacheDir.toString(), "routing-graph-cache", osmFile.name).toString()
            )
        }
    }

    /**
     * Init GTFS routing.
     */
    private fun setupGTFS() {
        // Prepare the GTFS data
        if (gtfsComponents == null) {
            gtfsComponents = GTFSComponents(focusArea, fullArea, cacheDir, dispatcher, osmFile)
        }
    }

    /**
     * Bundle of everything required for GTFS routing.
     */
    private inner class GTFSComponents(
        focusArea: Geometry, fullArea: Geometry, cacheDir: Path, dispatcher: CoroutineDispatcher,
        osmFile: File
    ) {
        val timeZone: TimeZone
        val ptSimDays: Map<Weekday, LocalDate>
        val ptRouter: PtRouter
        val gtfsHopper: GraphHopperGtfs

        init {
            // Bounds
            val latMin = fullArea.envelopeInternal.minX
            val lonMin = fullArea.envelopeInternal.minY
            val latMax = fullArea.envelopeInternal.maxX
            val lonMax = fullArea.envelopeInternal.maxY

            // Unique cache path
            val gtfsCachePath = Paths.get(
                cacheDir.toString(),
                "gtfs/",
                "BoundsLLAT${latMin}ULAT${latMax}LLON${lonMin}ULON${lonMax}"
            )

            // Create directory in cache
            Files.createDirectories(gtfsCachePath)

            // Clip GTFS file to bounding box
            val clippedGtfsPath = Paths.get(gtfsCachePath.toString(), "clippedGTFS")
            if (!clippedGtfsPath.exists()) {
                clipGTFSFile(
                    fullArea.envelopeInternal,
                    gtfsFile!!.toPath(),
                    gtfsCachePath,
                    dispatcher
                )
            }

            // Extract temporal information
            ptSimDays = getPublicTransitSimDays(Paths.get(clippedGtfsPath.toString(), "calendar.txt"))
            timeZone = getTimeZone(focusArea)

            // Get the GTFS GraphHopper
            val gtfsPair = createGraphHopperGTFS(
                osmFile.toString(),
                clippedGtfsPath.toString(),
                Paths.get(gtfsCachePath.toString(), "gtfs-routing-graph-cache", osmFile.name).toString()
            )
            ptRouter = gtfsPair.first
            gtfsHopper = gtfsPair.second
        }
    }

    /**
     * Determine time zone at the center of the focus area.
     */
    private fun getTimeZone(focusArea: Geometry) : TimeZone {
        val map = TimeZoneMap.forRegion(
            focusArea.envelopeInternal.minX, focusArea.envelopeInternal.minY,
            focusArea.envelopeInternal.maxX, focusArea.envelopeInternal.maxY
        )
        val tzString = map.getOverlappingTimeZone(focusArea.centroid.x, focusArea.centroid.y)?.zoneId
        return  TimeZone.getTimeZone(tzString)
    }
}
