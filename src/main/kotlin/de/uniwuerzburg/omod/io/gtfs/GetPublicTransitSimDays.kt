package de.uniwuerzburg.omod.io.gtfs

import de.uniwuerzburg.omod.core.models.Weekday
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.inputStream

/**
 * Determine the dates in the GTFS file where the most services are active. For every weekday.
 * These dates will represent the weekdays in the simulation.
 */
fun getPublicTransitSimDays( calendarTXTPath: Path ) : Map<Weekday, LocalDate> {
    val inputStream = calendarTXTPath.inputStream()
    val reader = inputStream.bufferedReader()

    // Regex for csv separator. ',' but not in quotations.
    val delimiter = Regex(""",(?=(?:[^"]*"[^"]*")*[^"]*${'$'})""")

    // Parse header
    val header = reader.readLine()
    val idxMap = header.split(delimiter).withIndex().associate { (i, v) -> v to i }
    val wdIdxMap = mapOf(
        1 to idxMap["monday"]!!,
        2 to idxMap["tuesday"]!!,
        3 to idxMap["wednesday"]!!,
        4 to idxMap["thursday"]!!,
        5 to idxMap["friday"]!!,
        6 to idxMap["saturday"]!!,
        7 to idxMap["sunday"]!!,
    )

    // Formatter
    val parser = DateTimeFormatter.ofPattern("yyyyMMdd")

    // Determine weekdays with most active services
    val days = mutableMapOf<LocalDate, Int>()
    reader.lineSequence().forEach { record ->
        val values = record.split(delimiter)
        val startDate = LocalDate.parse(values[idxMap["start_date"]!!], parser)
        val endDate = LocalDate.parse(values[idxMap["end_date"]!!], parser)
        var currentDate = startDate
        while (!currentDate.isAfter(endDate)) {
            val weekday = currentDate.dayOfWeek.value
            if (values[wdIdxMap[weekday]!!].toInt() == 1) {
                val cnt = days.getOrDefault(currentDate, 0)
                days[currentDate] = cnt + 1
            }
            currentDate = currentDate.plusDays(1)
        }
    }

    // Map simulation weekdays to real days
    val simDays = mutableMapOf<Weekday, LocalDate>()
    val ordinaryWds = listOf(Weekday.MO, Weekday.TU, Weekday.WE, Weekday.TH, Weekday.FR, Weekday.SA, Weekday.SU)
    for ((i, wd) in ordinaryWds.withIndex()) {
        simDays[wd] = days.filter { it.key.dayOfWeek.value == (i + 1) }.maxBy { it.value }.key
    }
    simDays[Weekday.HO] = days.filter { it.key.dayOfWeek.value == 7 }.maxBy { it.value }.key
    simDays[Weekday.UNDEFINED] = days.filter { it.key.dayOfWeek.value == 3 }.maxBy { it.value }.key

    // Close all
    reader.close()
    inputStream.close()
    return simDays
}