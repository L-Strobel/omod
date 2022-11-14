import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.10"
    kotlin("plugin.serialization") version "1.5.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("java")
    application
}

group = "de.uniwuerzburg"
version = "0.5"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    implementation("org.locationtech.jts:jts-core:1.19.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.postgresql:postgresql:42.5.0")
    implementation("com.github.ajalt.clikt:clikt:3.5.0")
    implementation("com.graphhopper:graphhopper-core:5.3")
    implementation("org.slf4j:slf4j-simple:2.0.0")
    implementation("org.openstreetmap.osmosis:osmosis-pbf:0.48.3")
    implementation("org.openstreetmap.osmosis:osmosis-areafilter:0.48.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.shadowJar {
    relocate("com.graphhopper", "com.graphhopper.shadow")
}

application {
    mainClass.set("de.uniwuerzburg.MainKt")
}