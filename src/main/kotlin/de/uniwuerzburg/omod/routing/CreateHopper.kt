package de.uniwuerzburg.omod.routing

import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
import com.graphhopper.jackson.Jackson
import com.graphhopper.routing.weighting.custom.CustomProfile
import com.graphhopper.util.CustomModel
import de.uniwuerzburg.omod.core.Omod

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

    // Custom Profile
    val cp = CustomProfile("custom_car")
    val configURL = Omod::class.java.classLoader.getResource("ghConfig.json")!!
    val cm: CustomModel = Jackson.newObjectMapper().readValue(configURL, CustomModel::class.java)
    cp.customModel = cm

    hopper.setProfiles(cp)
    hopper.chPreparationHandler.setCHProfiles(CHProfile("custom_car"))
    hopper.importOrLoad()
    logger.info("GraphHopper initialized!")
    return hopper
}