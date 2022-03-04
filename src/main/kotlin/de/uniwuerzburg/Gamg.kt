package de.uniwuerzburg

import java.io.BufferedReader
import java.io.FileReader
import org.locationtech.jts.index.kdtree.KdTree
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope

/**
 * Simplified landuse for work and home probabilities
 */
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

/**
 * Model for a building
 *
 * @param coord coordinates in meters
 * @param area area of the building in meters
 * @param population population of building. Can be non-integer.
 * @param landuse OSM-Landuse of the building
 */
data class Building(
    var coord: Coordinate,
    var area: Double,
    var population: Double,
    var landuse: Landuse
)

/**
 * General purpose mobility demand generator (gamg)
 *
 * Creates daily mobility profiles in the form of activity chains and dwell times.
 */
class Gamg(buildingsPath: String) {
    private val buildings: MutableList<Building>
    private val kdTree: KdTree

    init {
        val reader = BufferedReader(FileReader(buildingsPath))

        // Skip header
        reader.readLine()
        // Read body
        buildings = mutableListOf<Building>()
        reader.forEachLine {
            val line = it.split(",")
            buildings.add(Building(
                coord = Coordinate(line[1].toDouble(), line[2].toDouble()),
                area = line[0].toDouble(),
                population = line[3].toDouble(),
                landuse = Landuse.getFromStr(line[4])))
        }

        // Create KD-Tree for faster access
        kdTree = KdTree()
        buildings.forEachIndexed { i, building ->
            kdTree.insert(building.coord, i)
        }

        val tmp = kdTree.query(Envelope(1279107.0, 1281107.0, 6119160.0,  6120160.0))
        println(tmp.map { it.getData() })
    }

    fun createAgents() {

    }
}
