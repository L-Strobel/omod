package de.uniwuerzburg

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

internal class OmodTest {

    private val testBuildings = File(OmodTest::class.java.classLoader.getResource("testBuildings.geojson")!!.file)
    private val testOmod = Omod.fromFile( testBuildings)

    @Suppress("USELESS_CAST")
    @Test
    fun createAgents() {
        val popTxt = OmodTest::class.java.classLoader.getResource("testPopulation.json")!!.readText(Charsets.UTF_8)
        val populationDef = Json.decodeFromString<Map<String, Map<String, Double>>>(popTxt)
        val agents = testOmod.createAgents(100, inputPopDef = populationDef)
        val sum = agents.sumOf { if (it.age == "undefined") 1 as Int else 0 as Int}
        assertEquals(100, sum)
    }
}