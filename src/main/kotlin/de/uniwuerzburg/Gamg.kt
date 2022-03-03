package de.uniwuerzburg

import java.io.BufferedReader
import java.io.FileReader
import java.util.*


enum class Landuse {
    RESIDENTIAL, INDUSTRIAL, COMMERCIAL, OTHER;

    companion object {
        fun getFromStr(str: String) : Landuse {
            return when(str) {
                "Residential" -> RESIDENTIAL
                "Industrial"  -> INDUSTRIAL
                "Commercial"  -> COMMERCIAL
                else -> OTHER
            }
        }
    }
}

data class Building(
    var x: Double,
    var y: Double,
    var area: Double,
    var population: Double,
    var landuse: Landuse
)

class Gamg(buildingsPath: String) {
    var buildings: MutableList<Building>

    init {
        val reader = BufferedReader(FileReader(buildingsPath))

        reader.readLine()

        buildings = mutableListOf<Building>()
        reader.forEachLine {
            val line = it.split(",")
            buildings.add(Building(
                x = line[1].toDouble(),
                y = line[2].toDouble(),
                area = line[0].toDouble(),
                population = line[3].toDouble(),
                landuse = Landuse.getFromStr(line[4])))
        }

        println(buildings[0])
    }

    fun createAgents() {

    }
}
