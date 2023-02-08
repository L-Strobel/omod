package de.uniwuerzburg.omod

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.*
import de.uniwuerzburg.omod.assignment.allOrNothing
import de.uniwuerzburg.omod.core.Omod
import de.uniwuerzburg.omod.core.Weekday
import de.uniwuerzburg.omod.io.formatOutput
import de.uniwuerzburg.omod.routing.RoutingMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue


@Suppress("PrivatePropertyName")
class Run : CliktCommand() {
    // Arguments
    private val area_geojson by argument(
        help = "Path to the area you want OMOD to generate mobility demand for. " +
                "Must be a geojson. " +
                "Helpful sides for geojson generation: geojson.io, polygons.openstreetmap.fr"
    ).file(mustExist = true, mustBeReadable = true)
    private val osm_file by argument(
        help = "Path to osm.pbf file that includes the area completely. " +
               "Must cover the entire area, but can cover more. " +
               "However, Large files can slow down initialisation. " +
               "Recommended download platform: https://download.geofabrik.de/"
    ).file(mustExist = true, mustBeReadable = true)
    // Options
    private val n_agents by option(
        help="Number of agents in focus area to simulate." +
             "The buffer area and in-commuting sources are populated proportionally if populate_buffer_area = y."
    ).int().default(1000)
    private val n_days by option(
        help="Number of days to simulate"
    ).int().default(1)
    private val start_wd by option(
        help="First weekday to simulate. IF undefined n undefined days are simulated."
    ).enum<Weekday>().default(Weekday.UNDEFINED)
    private val out by option (
        help="Output file, must end on .json"
    ).file().default(File("output.json"))
    private val routing_mode by option(
        help = "Method of distance calculation. Either with euclidean distance (BEELINE) or routed using a car (GRAPHHOPPER)"
    ).enum<RoutingMode>().default(RoutingMode.BEELINE)
    private val od by option(
        help="Experimental. Path to an OD-Matrix in geojson format that will be used for calibration"
    ).file(mustExist = true, mustBeReadable = true)
    private val census by option(
        help="Path to population census in geojson format. See python_tools/format_zensus2011.py." +
             "For an example of how to create such a file." +
             "Should cover the entire area, but can cover more."
    ).file(mustExist = true, mustBeReadable = true)
    private val grid_res by option(
        help="Size of the grid cells used for quicker sampling. The 500m default is suitable in most cases. Unit: meters"
    ).double().default(500.0)
    private val buffer by option(
        help="Size of the buffer area that is simulated in addition to the specified focus area" +
              " to allow for short distance commutes. Unit: meters"
    ).double().default(0.0)
    private val seed by option(help = "Random seed to use. Like java.util.Random()").long()
    // private val cache by option(help = "Defines if the program caches the model area.")
    //     .choice("true" to true, "false" to false).default(true)
    private val cache_dir by option(help = "Location of cache.")
        .path(canBeDir = true, canBeFile = false).default(Paths.get("omod_cache/"))
    private val populate_buffer_area by option(
        help = "Set if agents can life in the buffer area?"
    ).choice( mapOf("y" to true, "n" to false), ignoreCase = true).default(true)
    private val distance_matrix_cache_size by option(
        help = "Size of the distance matrix to precompute if routing_mode is GRAPHHOPPER. " +
               "Size will be distance_matrix_cache_size x distance_matrix_cache_size. " +
               "A high value will lead to high RAM usage and long initialization times " +
               "but overall significant speed gains. Especially then rerunning the same area."
    ).int().default(20_000)
    private val assign_trips by option(
        help = "Experimental. Assign trips to routes using an all-or-nothing approach. " +
               "All trips are driven by car."
    ).choice( mapOf("y" to true, "n" to false), ignoreCase = true).default(false)
    private val assign_with_path by option(
        help = "Experimental. Output the path each trip is assigned to; " +
                "otherwise only time and distance is returned in the assignment output. " +
                "Only relevant if assign_trips == y."
    ).choice( mapOf("y" to true, "n" to false), ignoreCase = true).default(false)
    //@OptIn(ExperimentalTime::class)
    @OptIn(ExperimentalTime::class)
    override fun run() {
        val (omod, timeRead) = measureTimedValue {
            Omod(
                area_geojson, osm_file,
                mode = routing_mode,
                odFile = od, censusFile = census,
                gridResolution = grid_res, bufferRadius = buffer, seed = seed,
                cache = true, cacheDir = cache_dir,
                populateBufferArea = populate_buffer_area,
                distanceCacheSize = distance_matrix_cache_size
            )
        }
        println("Loading data took: $timeRead")

        // Mobility demand
        val (agents, timeSim) = measureTimedValue {
            omod.run(n_agents, start_wd, n_days)
        }
        println("Simulation took: $timeSim")
        out.writeText(Json.encodeToString(agents.map { formatOutput(it) }))

        // Assignment
        if (assign_trips) {
            val hopper = omod.hopper

            if (hopper == null) {
                println("Assignment only possible in GRAPHHOPPER mode.")
            } else {
                val (assignment, timeAssign) = measureTimedValue { allOrNothing(agents, hopper, assign_with_path) }
                println("Assignment took: $timeAssign")

                File("assignment.json").writeText(Json.encodeToString(assignment))
            }
        }
    }
}

fun main(args: Array<String>) = Run().main(args)
