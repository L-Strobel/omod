import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.+"
    kotlin("plugin.serialization") version "1.+"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("java")
    id("org.jetbrains.dokka") version "1.8.10"
    application
}

group = "de.uniwuerzburg.omod"
version = "1.8.3"

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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.+")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.+")
    implementation("org.locationtech.jts:jts-core:1.+")
    implementation("org.apache.commons:commons-math3:3.+")
    implementation("com.github.ajalt.clikt:clikt:4.+")
    implementation("com.graphhopper:graphhopper-core:9.+")
    implementation("ch.qos.logback:logback-classic:1.+")
    implementation("org.openstreetmap.osmosis:osmosis-pbf:0.48.+")
    implementation("org.openstreetmap.osmosis:osmosis-areafilter:0.48.+")
    implementation("org.geotools:gt-main:27.+")
    implementation("org.geotools:gt-epsg-hsql:27.+")
    implementation("com.google.guava:guava:33.2.1-jre")
    testImplementation("org.junit.jupiter:junit-jupiter:5.+")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("de.uniwuerzburg.omod.MainKt")
}