package de.uniwuerzburg.omod.io.json

import de.uniwuerzburg.omod.core.Omod
import de.uniwuerzburg.omod.io.jsonHandler
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.nio.file.Path

inline fun <reified T>readJson(path: Path): T {
    return readJson(path.toFile())
}

inline fun <reified T> readJson(file: File): T {
    return readJson(file.readText(Charsets.UTF_8))
}

inline fun <reified T> readJsonFromResource(res: String): T {
    val txt = Omod::class.java.classLoader.getResource(res)!!.readText(Charsets.UTF_8)
    return jsonHandler.decodeFromString(txt)
}

inline fun <reified T> readJson(txt: String): T {
    return jsonHandler.decodeFromString(txt)
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> readJsonStream(file: File): T {
    return jsonHandler.decodeFromStream(file.inputStream())
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> readJsonStream(path: Path): T {
    return jsonHandler.decodeFromStream(path.toFile().inputStream())
}