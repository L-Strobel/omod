package de.uniwuerzburg.omod.io

import kotlinx.serialization.json.Json

val json = Json { encodeDefaults = true; ignoreUnknownKeys = true}