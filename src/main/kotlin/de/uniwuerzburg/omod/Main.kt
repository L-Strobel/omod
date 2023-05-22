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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue


@Suppress("PrivatePropertyName")
class Run : CliktCommand() {
    // Arguments
    private val area_geojson by argument(
        help = "Path to the GeoJSON file that defines the area for which you want to generate mobility demand. " +
                "Helpful websites for GeoJSON generation: https://geojson.io, https://polygons.openstreetmap.fr"
    ).file(mustExist = true, mustBeReadable = true)
    private val osm_file by argument(
        help = "Path to an osm.pbf file that covers the area completely. " +
               "Recommended download platform: https://download.geofabrik.de/"
    ).file(mustExist = true, mustBeReadable = true)
    // Options
    private val n_agents by option(
        help="Number of agents to simulate. " +
             "If populate_buffer_area = y, additional agents are created to populate the buffer area"
    ).int().default(1000)
    private val n_days by option(
        help="Number of days to simulate"
    ).int().default(1)
    private val start_wd by option(
        help="First weekday to simulate. If the value is set to UNDEFINED, all simulated days will be UNDEFINED."
    ).enum<Weekday>().default(Weekday.UNDEFINED)
    private val out by option (
        help="Output file. Should end with '.json'"
    ).file().default(File("output.json"))
    private val routing_mode by option(
        help = "Distance calculation method. Either euclidean distance (BEELINE) or routed distance by car (GRAPHHOPPER)"
    ).enum<RoutingMode>().default(RoutingMode.BEELINE)
    private val od by option(
        help="[Experimental] Path to an OD-Matrix in GeoJSON format. " +
             "The matrix is used to further calibrate the model to the area using k-factors."
    ).file(mustExist = true, mustBeReadable = true)
    private val census by option(
        help="Path to population data in GeoJSON format. " +
             "For an example of how to create such a file see python_tools/format_zensus2011.py. " +
             "Should cover the entire area, but can cover more."
    ).file(mustExist = true, mustBeReadable = true)
    private val grid_precision by option(
        help="Allowed average distance between focus area building and its corresponding TAZ center. " +
             "The default is 200m and suitable in most cases." +
             "In the buffer area the allowed distance increases quadratically with distance. " +
             "Unit: meters"
    ).double().default(200.0)
    private val buffer by option(
        help="Size of the buffer area that is simulated in addition to the area specified in the GeoJSON. Unit: meters"
    ).double().default(0.0)
    private val seed by option(help = "RNG seed.").long()
    // private val cache by option(help = "Defines if the program caches the model area.")
    //     .choice("true" to true, "false" to false).default(true)
    private val cache_dir by option(help = "Cache directory")
        .path(canBeDir = true, canBeFile = false).default(Paths.get("omod_cache/"))
    private val populate_buffer_area by option(
        help = "Determines if home locations of agents can be in the buffer area. " +
               "If set to 'y' additional agents will be created so that the proportion of agents in and " +
               "outside the focus area is the same as in the census data. " +
               "The focus area will always be populated by n_agents agents."
    ).choice( mapOf("y" to true, "n" to false), ignoreCase = true).default(false)
    private val distance_matrix_cache_size by option(
        help = "Size of the distance matrix to precompute (only if routing_mode is GRAPHHOPPER). " +
               "A high value will lead to high RAM usage and long initialization times " +
               "but overall significant speed gains. Especially then rerunning the same area."
    ).int().default(20_000)
    private val assign_trips by option(
        help = "[Experimental] Assign trips to routes using an all-or-nothing approach. " +
               "All trips are driven by car."
    ).choice( mapOf("y" to true, "n" to false), ignoreCase = true).default(false)
    private val assign_with_path by option(
        help = "[Experimental] Output the path coordinates of each trip. " +
               "Only relevant if assign_trips == y."
    ).choice( mapOf("y" to true, "n" to false), ignoreCase = true).default(false)
    private val population_file by option(
        help="Path to file that describes the socio-demographic makeup of the population. " +
             "Must be formatted like omod/src/main/resources/Population.json."
    ).file(mustExist = true, mustBeReadable = true)
    //@OptIn(ExperimentalTime::class)
    @OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
    override fun run() {
        val (omod, timeRead) = measureTimedValue {
            Omod(
                area_geojson, osm_file,
                mode = routing_mode,
                odFile = od, censusFile = census,
                gridPrecision = grid_precision, bufferRadius = buffer, seed = seed,
                cache = true, cacheDir = cache_dir,
                populateBufferArea = populate_buffer_area,
                distanceCacheSize = distance_matrix_cache_size,
                populationFile = population_file
            )
        }
        println("Loading data took: $timeRead")

        // Mobility demand
        val (agents, timeSim) = measureTimedValue {
            omod.run(n_agents, start_wd, n_days)
        }

        println("Simulation took: $timeSim")

        // Store output
        FileOutputStream(out).use { f ->
            Json.encodeToStream(agents.map { formatOutput(it) }, f)
        }

        // Assignment
        if (assign_trips) {
            val hopper = omod.hopper

            if (hopper == null) {
                println("Assignment only possible in GRAPHHOPPER mode.")
            } else {
                val (assignment, timeAssign) = measureTimedValue {
                    allOrNothing(agents, hopper, omod.transformer, assign_with_path)
                }
                println("Assignment took: $timeAssign")
                // Store assignment output
                val assignOut = File(out.parent, out.nameWithoutExtension  + "_trips.json")
                FileOutputStream(assignOut).use { f ->
                    Json.encodeToStream(assignment, f)
                }
            }
        }
    }
}

fun main(args: Array<String>) = Run().main(args)
