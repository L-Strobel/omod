import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.21"
    kotlin("plugin.serialization") version "1.7.21"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("java")
    id("org.jetbrains.dokka") version "1.8.10"
    application
}

group = "de.uniwuerzburg.omod"
version = "1.8.2"

repositories {
    mavenLocal()
    maven {
        url = uri("https://repo.osgeo.org/repository/release/")
    }
    maven {
        url = uri("https://repo.osgeo.org/repository/snapshot/")
    }
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
    implementation("org.locationtech.jts:jts-core:1.19.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("com.github.ajalt.clikt:clikt:3.5.2")
    implementation("com.graphhopper:graphhopper-core:7.0")
    implementation("ch.qos.logback:logback-classic:1.4.7")
    implementation("org.openstreetmap.osmosis:osmosis-pbf:0.48.3")
    implementation("org.openstreetmap.osmosis:osmosis-areafilter:0.48.3")
    implementation("org.geotools:gt-main:27.1")
    implementation("org.geotools:gt-epsg-hsql:27.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("de.uniwuerzburg.omod.MainKt")
}