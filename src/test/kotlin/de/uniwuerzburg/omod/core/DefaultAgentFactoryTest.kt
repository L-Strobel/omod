package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.ActivityType
import de.uniwuerzburg.omod.core.models.Building
import de.uniwuerzburg.omod.core.models.Cell
import de.uniwuerzburg.omod.core.models.PopulationDef
import de.uniwuerzburg.omod.io.geojson.GeoJsonFeatureCollection
import de.uniwuerzburg.omod.io.json.readJson
import de.uniwuerzburg.omod.io.json.readJsonFromResource
import de.uniwuerzburg.omod.routing.RoutingCache
import de.uniwuerzburg.omod.routing.RoutingMode
import de.uniwuerzburg.omod.utils.CRSTransformer
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.io.File
import java.util.*

class DefaultAgentFactoryTest {

    @Test
    fun createAgentsSharePopTest() {
        // Setup
        val mutLocChoiceFuns: MutableMap<ActivityType, LocationChoiceDCWeightFun> =
            readJsonFromResource("LocChoiceWeightFuns.json")
        mutLocChoiceFuns[ActivityType.HOME] = ByPopulation
        mutLocChoiceFuns[ActivityType.BUSINESS] = mutLocChoiceFuns[ActivityType.OTHER]!!
        val locChoiceWeightFuns = mutLocChoiceFuns.toMap()

        val routingCache = RoutingCache(RoutingMode.BEELINE, null, 0, Dispatchers.Default)
        val destinationFinder = DefaultDestinationFinder(routingCache, locChoiceWeightFuns)
        val populationDef: PopulationDef = readJsonFromResource("Population.json")
        val agentFactory = DefaultAgentFactory(destinationFinder, populationDef, Dispatchers.Default)

        // Get buildings
        val buildingFile = File(Omod::class.java.classLoader.getResource("testBuildings.geojson")!!.file)
        val collection: GeoJsonFeatureCollection = readJson(buildingFile)
        val buildings =  Building.fromGeoJson(
            collection, GeometryFactory(), CRSTransformer( 11.630883577905143 ), locChoiceWeightFuns
        )
        val cell = Cell(
            id = 0,
            coord = Coordinate(0.0, 0.0),
            latlonCoord = Coordinate(0.0, 0.0),
            buildings = buildings,
        )
        for (building in buildings) {
            building.cell = cell
        }

        // Run
        val agents = agentFactory.createAgents(1.0, listOf(cell), false, Random())
        assert(agents.filter { (it.home as? Building)?.osmID == 454202426.toLong() }.size == 2)
    }
}