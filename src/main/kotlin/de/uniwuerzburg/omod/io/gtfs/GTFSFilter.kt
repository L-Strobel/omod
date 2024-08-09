package de.uniwuerzburg.omod.io.gtfs

interface GTFSFilter {
    fun filter(record: List<String>, idxMap: Map<String, Int>) : Boolean
}