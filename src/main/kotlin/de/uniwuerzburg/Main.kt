package de.uniwuerzburg

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.check
import com.github.ajalt.clikt.parameters.arguments.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths

@Suppress("PrivatePropertyName")
class Run : CliktCommand() {
    // Arguments
    private val db_url by argument()
    private val db_user by argument()
    private val db_password by argument()
    private val area_osm_ids by argument()
        .convert { input -> input.trim().splitToSequence(',').map { it.toInt() }.toList() }
        .check("Reading osm ids failed! Expected format \"name1,name2,name3\".") { it.isNotEmpty() }
    // Options
    private val n_agents by option(
        help="Number of agents to simulate"
    ).int().default(1000)
    private val n_days by option(
        help="Number of days to simulate"
    ).int().default(1)
    private val start_wd by option(
        help="First weekday to simulate"
    ).choice(*weekdays.toTypedArray()).default("mo")
    private val out by option (
        help="Output file, must end on .json"
    ).file().default(File("output.json"))
    private val od by option(
        help="Path to an OD-Matrix in geojson format that will be used for calibration"
    ).file(mustExist = true, mustBeReadable = true)
    private val census by option(
        help="Path to population census in geojson format. For example see regional_inputs/." +
              "Should cover the entire area, but can cover more"
    ).file(mustExist = true, mustBeReadable = true)
    private val region_types by option(
        help="Path to region type information in geojson format. For example see regional_inputs/." +
             "Can only cover parts of the area."
    ).file(mustExist = true, mustBeReadable = true)
    private val grid_res by option(
        help="Size of the grid cells used for quicker sampling. The 500m default is suitable in most cases. Unit: meters"
    ).double().default(500.0)
    private val buffer by option(
        help="Size of the buffer area that is simulated in addition to the specified focus area" +
              " to allow for short distance commutes. Unit: meters"
    ).double().default(0.0)
    private val seed by option(help = "Random seed to use. Like java.util.Random()").long()
    private val cache by option(help = "Defines if the program caches the model area.")
        .choice("true" to true, "false" to false).default(true)
    private val cache_path by option(help = "Location of cache.")
        .path().default(Paths.get("omod_cache/buildings.geojson"))

    //@OptIn(ExperimentalTime::class)
    override fun run() {
        val omod = Omod.fromPG(
            db_url, db_user, db_password, area_osm_ids,
            odFile = od, censusFile = census, regionTypeFile = region_types,
            gridResolution = grid_res, bufferRadius = buffer, seed = seed,
            cache = cache, cachePath = cache_path
        )
        val agents = omod.run(n_agents, start_wd, n_days)
        /*
        val (omod, timeRead) = measureTimedValue {
            Omod.fromPG(
                db_url, db_user, db_password, area_osm_ids,
                odFile = od, censusFile = census, regionTypeFile = region_types,
                gridResolution = grid_res, bufferRadius = buffer, seed = seed,
                cache = cache, cachePath = cache_path
            )
        }
        println("Loading data took: ${timeRead.inWholeSeconds} secs")

        val (agents, timeSim) = measureTimedValue {
            omod.run(n_agents, start_wd, n_days)
        }
        println("Simulation took: ${timeSim.inWholeSeconds} secs")
         */
        out.writeText(Json.encodeToString(agents.map { formatOutput(it) }))
    }
}

/* Speed test of OSM reading
fun main() {
    val elapsed = measureTimeMillis {
        Omod.makeFileFromPG(
            File("omod_cache/bav.geojson"),
            "jdbc:postgresql://localhost:5432/OSM_Ger",
            "postgres",
            "password",
            listOf(62428),
            regionTypeFile = File("C:/Users/strobel/Projekte/esmregio/Daten/InputOMOD/region_types.geojson"),
            bufferRadius = 5000.0,
        )
    }
    println(elapsed / 1000)
}*/

fun main(args: Array<String>) = Run().main(args)
