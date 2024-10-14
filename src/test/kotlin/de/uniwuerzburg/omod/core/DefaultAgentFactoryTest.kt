package de.uniwuerzburg.omod.core

import de.uniwuerzburg.omod.core.models.*
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
    fun ageTest() {
        // Setup
        val mutLocChoiceFuns: MutableMap<ActivityType, LocationChoiceDCWeightFun> =
            readJsonFromResource("LocChoiceWeightFuns.json")
        mutLocChoiceFuns[ActivityType.HOME] = ByPopulation
        mutLocChoiceFuns[ActivityType.BUSINESS] = mutLocChoiceFuns[ActivityType.OTHER]!!
        val locChoiceWeightFuns = mutLocChoiceFuns.toMap()

        val routingCache = RoutingCache(RoutingMode.BEELINE, null, 0, Dispatchers.Default)
        val destinationFinder = DefaultDestinationFinder(routingCache, locChoiceWeightFuns)

        val popStrata: List<PopStratum> = readJsonFromResource("testPopulation.json")
        val agentFactory = DefaultAgentFactory(destinationFinder, popStrata, Dispatchers.Default)

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

        val agents = agentFactory.createAgents(10_000, listOf(cell), false, Random())
        val sumUNDEFINED = agents.filter {it.age == null}.size
        val notNones = agents.filter {it.age != null}
        val sumBelow10 = notNones.map{if (it.age!! < 10) 1 else 0}.sum()
        val sumBetween1050 = notNones.map{if ((it.age!! >= 10) and (it.age!! < 50)) 1 else 0}.sum()

        // Check if shares are within generous bounds. Might fail very rarely by chance.
        assert(sumUNDEFINED > 10_000 * 0.0)
        assert(sumUNDEFINED < 10_000 * 0.4)
        assert(sumBelow10 > 10_000 * 0.2)
        assert(sumBelow10 < 10_000 * 0.8)
        assert(sumBetween1050 > 10_000 * 0.1)
        assert(sumBetween1050 < 10_000 * 0.6)
        assert(sumUNDEFINED + sumBelow10 + sumBetween1050 == 10_000 )
    }

    @Test
    fun popStrataTest() {
        // Setup
        val mutLocChoiceFuns: MutableMap<ActivityType, LocationChoiceDCWeightFun> =
            readJsonFromResource("LocChoiceWeightFuns.json")
        mutLocChoiceFuns[ActivityType.HOME] = ByPopulation
        mutLocChoiceFuns[ActivityType.BUSINESS] = mutLocChoiceFuns[ActivityType.OTHER]!!
        val locChoiceWeightFuns = mutLocChoiceFuns.toMap()

        val routingCache = RoutingCache(RoutingMode.BEELINE, null, 0, Dispatchers.Default)
        val destinationFinder = DefaultDestinationFinder(routingCache, locChoiceWeightFuns)

        val popStrata: List<PopStratum> = readJsonFromResource("testPopulation.json")
        val agentFactory = DefaultAgentFactory(destinationFinder, popStrata, Dispatchers.Default)

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

        val agents = agentFactory.createAgents(100, listOf(cell), false, Random())
        assert(agents.all { it.homogenousGroup == HomogeneousGrp.UNDEFINED })
        assert(agents.all { it.mobilityGroup == MobilityGrp.UNDEFINED })
        assert(agents.all { it.sex == Sex.UNDEFINED })
    }

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
        val popStrata: List<PopStratum> = readJsonFromResource("testPopulation.json")
        val agentFactory = DefaultAgentFactory(destinationFinder, popStrata, Dispatchers.Default)

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