package de.uniwuerzburg.omod.io.json


import de.uniwuerzburg.omod.io.jsonHandler
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path


@Suppress("unused")
inline fun <reified T> writeJson(data: T, path: Path) {
    writeJson(data, path.toFile())
}

inline fun <reified T> writeJson(data: T, file: File) {
    file.writeText(jsonHandler.encodeToString(data))
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> writeJsonStream(data: T,  path: Path) {
    FileOutputStream(path.toFile()).use { f ->
        jsonHandler.encodeToStream( data, f)
    }
}

