package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate

internal class GamgTest {

    private val testGamg = Gamg("src/test/resources/testBuildings.csv", 500.0)
    private val testAgent = MobiAgent(0, "undefined", "undefined", "undefined", 0, 0)

    // Test giving custom starting coordinates
    @Test
    fun getMobilityProfile() {
        val xExpected = 1285755.5189086606
        val yExpected = 6126968.5279632155
        val coords = Coordinate(xExpected, yExpected)
        val result = testGamg.getMobilityProfile(testAgent, fromCoords = coords )
        assertEquals(xExpected, result[0].x)
        assertEquals(yExpected, result[0].y)
    }

    @Suppress("USELESS_CAST")
    @Test
    fun createAgents() {
        val popTxt = GamgTest::class.java.classLoader.getResource("testPopulation.json")!!.readText(Charsets.UTF_8)
        val populationDef = Json.decodeFromString<Map<String, Map<String, Double>>>(popTxt)
        val agents = testGamg.createAgents(100, inputPopDef = populationDef)
        val sum = agents.sumOf { if (it.age == "undefined") 1 as Int else 0 as Int}
        assertEquals(100, sum)
    }
}