package de.uniwuerzburg.omod.routing

import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.GHUtility
import com.graphhopper.util.TranslationMap
import com.graphhopper.gtfs.GraphHopperGtfs
import com.graphhopper.gtfs.PtRouter
import com.graphhopper.gtfs.PtRouterImpl

/**
 * Create GraphHopper object.
 * @param osmLoc Path of osm.pbf file
 * @param cacheLoc Path where the cache should be stored
 * @return GraphHopper object
 */
fun createGraphHopper(osmLoc: String, cacheLoc: String) : GraphHopper {
    logger.info("Initializing GraphHopper... (If the osm.pbf is large this can take some time)")
    val hopper = GraphHopper()
    hopper.osmFile = osmLoc
    hopper.graphHopperLocation = cacheLoc
    hopper.setEncodedValuesString(
        "car_access, car_average_speed, road_class, foot_access, hike_rating, " +
        "foot_priority, foot_average_speed, bike_priority, bike_access, roundabout, bike_average_speed"
    )

    // Profiles
    val profiles = listOf(
        Profile("car"  ).setCustomModel(GHUtility.loadCustomModelFromJar("car.json")),
        Profile("foot" ).setCustomModel(GHUtility.loadCustomModelFromJar("foot.json")),
        Profile("bike" ).setCustomModel(GHUtility.loadCustomModelFromJar("bike.json"))
    )
    hopper.setProfiles(profiles)

    hopper.chPreparationHandler.setCHProfiles(
        CHProfile("car"),
        CHProfile("foot"),
        CHProfile("bike")
    )
    hopper.importOrLoad()
    logger.info("GraphHopper initialized!")
    return hopper
}

/**
 * Create GraphHopperGTFS object and the corresponding router.
 * @param osmLoc Path of osm.pbf file
 * @param cacheLoc Path where the cache should be stored
 * @return PtRouter and GraphHopperGTFS object
 */
fun createGraphHopperGTFS(osmLoc: String, gtfsLoc: String, cacheLoc: String) : Pair<PtRouter, GraphHopperGtfs> {
    logger.info("Initializing GraphHopperGTFS... (RAM intensive. Best use the smallest possible osm.pbf)")
    val ghConfig = GraphHopperConfig()
    ghConfig.putObject("graph.location", cacheLoc)
    ghConfig.putObject("gtfs.file", gtfsLoc)
    ghConfig.putObject("import.osm.ignored_highways", "motorway,trunk")
    ghConfig.putObject("datareader.file", osmLoc)
    ghConfig.putObject(
        "graph.encoded_values",
        "foot_access, foot_priority, foot_average_speed, hike_rating"
    )

    // Profiles
    ghConfig.setProfiles(
        listOf(
            Profile("foot").setCustomModel(GHUtility.loadCustomModelFromJar("foot.json"))
        )
    )

    val hopperGtfs = GraphHopperGtfs(ghConfig)
    hopperGtfs.chPreparationHandler.setCHProfiles(
        CHProfile("foot"),
    )

    hopperGtfs.init(ghConfig)
    hopperGtfs.importOrLoad()
    val ptRouter = PtRouterImpl.Factory(
        ghConfig,
        TranslationMap().doImport(),
        hopperGtfs.baseGraph,
        hopperGtfs.encodingManager,
        hopperGtfs.locationIndex,
        hopperGtfs.gtfsStorage
    ).createWithoutRealtimeFeed()
    logger.info("GraphHopperGTFS initialized!")
    return Pair(ptRouter, hopperGtfs)
}