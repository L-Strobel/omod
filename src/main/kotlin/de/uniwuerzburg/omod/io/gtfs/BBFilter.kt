package de.uniwuerzburg.omod.io.gtfs

import org.locationtech.jts.geom.Envelope

class BBFilter(
    envelope: Envelope
) : GTFSFilter {
    private val minX = envelope.minX
    private val maxX = envelope.maxX
    private val minY = envelope.minY
    private val maxY = envelope.maxY

    override fun filter(record: List<String>, idxMap: Map<String, Int>) : Boolean {
        val lat = record[idxMap["stop_lat"]!!].toDoubleOrNull() ?: return false
        if ((lat < minX) || (lat > maxX)) return false
        val lon = record[idxMap["stop_lon"]!!].toDoubleOrNull() ?: return false
        if ((lon < minY) || (lon > maxY)) return false
        return true
    }
}