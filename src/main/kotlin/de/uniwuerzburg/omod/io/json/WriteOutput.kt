package de.uniwuerzburg.omod.io.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalSerializationApi::class)
fun writeJSONOutput(output: List<OutputEntry>, file: File, runParams: Map<String, String>) : Boolean {

    val amendedOutput = OutputFormat(runParams, output)
    FileOutputStream(file).use { f ->
        Json.encodeToStream( amendedOutput, f)
    }
    return true
}