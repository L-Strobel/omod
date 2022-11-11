package de.uniwuerzburg.omod.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File

internal class OmodTest {

    private val testBuildings = File(OmodTest::class.java.classLoader.getResource("testBuildings.geojson")!!.file)
    private val testOmod = Omod.fromFile(testBuildings)

    @Test
    fun createAgents_features() {
        val popTxt = OmodTest::class.java.classLoader.getResource("testPopulation.json")!!.readText(Charsets.UTF_8)
        val populationDef = Json.decodeFromString<Map<String, Map<String, Double>>>(popTxt)
        val agents = testOmod.createAgents(100, inputPopDef = populationDef)
        assert( agents.all { it.age == "undefined"})
    }

    @Test
    fun createAgents_count() {
        val popTxt = OmodTest::class.java.classLoader.getResource("testPopulation.json")!!.readText(Charsets.UTF_8)
        val populationDef = Json.decodeFromString<Map<String, Map<String, Double>>>(popTxt)
        val agents = testOmod.createAgents(100, inputPopDef = populationDef)
        assert( agents.count { it.home.inFocusArea } == 100)
    }
}