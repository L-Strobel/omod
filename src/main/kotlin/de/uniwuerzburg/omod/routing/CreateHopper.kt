package de.uniwuerzburg.omod.routing

import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.util.GHUtility

/**
 * Create GraphHopper object.
 * @param osmLoc Path of osm.pbf file
 * @param cacheLoc Path where the cache should be stored
 * @return GraphHopper object
 */
fun createGraphHopper(osmLoc: String, cacheLoc: String) : GraphHopper {
    logger.info("Initializing GraphHopper... (If the osm.pbf is large this can take some time. " +
            "Change routing_mode to BEELINE for fast results.)")
    val hopper = GraphHopper()
    hopper.osmFile = osmLoc
    hopper.graphHopperLocation = cacheLoc
    hopper.setEncodedValuesString("car_access, car_average_speed, road_class")

    // Custom Profile
    val cm = GHUtility.loadCustomModelFromJar("car.json")
    val cp = Profile("custom_car").setCustomModel(cm)
    hopper.setProfiles(cp);

    hopper.chPreparationHandler.setCHProfiles(CHProfile("custom_car"))
    hopper.importOrLoad()
    logger.info("GraphHopper initialized!")
    return hopper
}