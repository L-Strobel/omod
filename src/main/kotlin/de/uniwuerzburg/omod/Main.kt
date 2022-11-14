package de.uniwuerzburg.omod

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.*
import de.uniwuerzburg.omod.core.Omod
import de.uniwuerzburg.omod.core.weekdays
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
    private val area_wkt by argument(
        help = "The area you want OMOD to generate mobility demand for. " +
                "Must be a WKT string in lat/lon coordinates. " +
                "See: https://en.wikipedia.org/wiki/Well-known_text_representation_of_geometry. " +
                "These strings can get very long, it can be helpful to store all command line arguments " +
                "in a file and use the @argfile syntax. " +
                "See: https://ajalt.github.io/clikt/advanced/#command-line-argument-files-argfiles"
    )
    private val osm_file by argument(
        help = "Path to osm.pbf file that includes the area completely. " +
               "Must cover the entire area, but can cover more. " +
               "However, Large files can slow down initialisation. " +
               "Recommended download platform: https://download.geofabrik.de/"
    ).file(mustExist = true, mustBeReadable = true)
    // Options
    private val n_agents by option(
        help="Number of agents in focus area to simulate." +
             "The buffer area and in-commuting sources are populated proportionally."
    ).int().default(1000)
    private val n_days by option(
        help="Number of days to simulate"
    ).int().default(1)
    private val start_wd by option(
        help="First weekday to simulate. IF undefined n undefined days are simulated."
    ).choice(*weekdays.toTypedArray() + listOf("undefined")).default("mo")
    private val out by option (
        help="Output file, must end on .json"
    ).file().default(File("output.json"))
    private val routing_mode by option(
        help = "Method of distance calculation. Either with euclidean distance (BEELINE) or routed using a car (GRAPHHOPPER)"
    ).enum<RoutingMode>().default(RoutingMode.BEELINE)
    private val od by option(
        help="Path to an OD-Matrix in geojson format that will be used for calibration"
    ).file(mustExist = true, mustBeReadable = true)
    private val census by option(
        help="Path to population census in geojson format. For example see regional_inputs/." +
              "Should cover the entire area, but can cover more"
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

    //@OptIn(ExperimentalTime::class)
    @OptIn(ExperimentalTime::class)
    override fun run() {
        /*
        val omod = Omod.fromPG(
            db_url, db_user, db_password, area_osm_ids,
            odFile = od, censusFile = census,
            gridResolution = grid_res, bufferRadius = buffer, seed = seed,
            cache = cache, cachePath = cache_path
        )
        val agents = omod.run(n_agents, start_wd, n_days)
        */
        val (omod, timeRead) = measureTimedValue {
            Omod(
                area_wkt, osm_file,
                mode = routing_mode,
                odFile = od, censusFile = census,
                gridResolution = grid_res, bufferRadius = buffer, seed = seed,
                cache = true, cacheDir = cache_dir
            )
        }
        println("Loading data took: $timeRead")

        val (agents, timeSim) = measureTimedValue {
            omod.run(n_agents, start_wd, n_days)
        }
        println("Simulation took: $timeSim")

        out.writeText(Json.encodeToString(agents.map { formatOutput(it) }))
    }
}

fun main(args: Array<String>) = Run().main(args)
