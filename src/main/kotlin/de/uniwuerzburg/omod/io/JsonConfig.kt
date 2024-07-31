package de.uniwuerzburg.omod.io

import kotlinx.serialization.json.Json

// Globally used json config
val jsonHandler = Json { encodeDefaults = true; ignoreUnknownKeys = true}