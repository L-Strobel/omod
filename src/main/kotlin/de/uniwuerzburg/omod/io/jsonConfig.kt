package de.uniwuerzburg.omod.io

import kotlinx.serialization.json.Json

// Globally used json config
val json = Json { encodeDefaults = true; ignoreUnknownKeys = true}