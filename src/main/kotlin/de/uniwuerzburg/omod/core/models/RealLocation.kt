package de.uniwuerzburg.omod.core.models

/**
 * A location that is inside the area with OSM data, i.e. not a dummy location
 *
 */
interface RealLocation : LocationOption {
    val population: Double
    val attractions: Map<Int, Double>
}