package de.uniwuerzburg.omod.io.gtfs

class ForeignKeyFilter(
    private val fKeys: Set<Int>,
    private val col: String
) : GTFSFilter {
    override fun filter(record: List<String>, idxMap: Map<String, Int>) : Boolean {
        return record[idxMap[col]!!].toInt() in fKeys
    }
}
