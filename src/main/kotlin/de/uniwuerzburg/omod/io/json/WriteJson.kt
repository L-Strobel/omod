package de.uniwuerzburg.omod.io.json

import de.uniwuerzburg.omod.io.jsonHandler
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Path

inline fun <reified T> writeJson(data: T, path: Path) {
    writeJson(data, path.toFile())
}

inline fun <reified T> writeJson(data: T, file: File) {
    file.writeText(jsonHandler.encodeToString(data))
}