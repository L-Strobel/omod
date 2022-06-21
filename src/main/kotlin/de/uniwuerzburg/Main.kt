package de.uniwuerzburg

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.measureTimeMillis

/*
fun runWeek(buildingPath: String, n: Int){
    val gamg = Gamg(buildingPath, 500.0)
    val agents = gamg.createAgents(n)
    for (weekday in listOf("mo", "tu", "we", "th", "fr", "sa", "so")) {
        for (agent in agents) {
            if (agent.profile == null){
                agent.profile = gamg.getMobilityProfile(agent, weekday)
            } else {
                val lastActivity = agent.profile!!.last()
                agent.profile = when (lastActivity.type) {
                    ActivityType.HOME, ActivityType.WORK, ActivityType.SCHOOL -> gamg.getMobilityProfile(agent, weekday, lastActivity.type)
                    else -> gamg.getMobilityProfile(agent, weekday, from = lastActivity.type, fromCoords = Coordinate(lastActivity.x, lastActivity.y))
                }
            }
        }
        File("validation/out/$weekday.json").writeText(Json.encodeToString(agents))
    }
}
*/
fun runDay(buildingPath: String, n: Int) {
    val gamg = Gamg(buildingPath, 500.0)
    val elapsed = measureTimeMillis {
        val agents = gamg.run(n)
        File("validation/out/singleUndefined.json").writeText(Json.encodeToString(agents))
    }
    println(elapsed / 1000.0)
}

fun main() {
    val buildingPath = "C:/Users/strobel/Projekte/esmregio/gamg/Buildings-62640,-1070986,-1070976,-1070979,-1071007,-1070996,-1071000,-1070985,-1070974.csv"
    runDay(buildingPath, 10000)
}
