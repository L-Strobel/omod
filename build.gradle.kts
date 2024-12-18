plugins {
    kotlin("jvm") version "2.+"
    kotlin("plugin.serialization") version "1.+"
    id("com.gradleup.shadow") version "8.+"
    id("java")
    id("org.jetbrains.dokka") version "2.0.+"
    id("maven-publish")
    application
}

group = "de.uniwuerzburg.omod"
version = "2.0.18"

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
    implementation("org.geotools:gt-epsg-hsql:31.+")
    implementation("org.geotools:gt-main:31.+")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.+")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.+")
    implementation("org.locationtech.jts:jts-core:1.+")
    implementation("org.apache.commons:commons-math3:3.+")
    implementation("com.github.ajalt.clikt:clikt:4.+")
    implementation("com.graphhopper:graphhopper-core:9.+")
    implementation("com.graphhopper:graphhopper-reader-gtfs:9.1")
    implementation("ch.qos.logback:logback-classic:1.+")
    implementation("org.openstreetmap.osmosis:osmosis-pbf:0.48.+")
    implementation("org.openstreetmap.osmosis:osmosis-areafilter:0.48.+")
    implementation("com.google.guava:guava:33.2.1-jre")
    implementation("us.dustinj.timezonemap:timezonemap:4.+")
    implementation("org.xerial:sqlite-jdbc:3.+")
    testImplementation("org.junit.jupiter:junit-jupiter:5.+")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.shadowJar {
    mergeServiceFiles()
}

publishing {
    publications {
        create<MavenPublication>("omod") {
            from(components["java"])
        }
    }
}

application {
    mainClass.set("de.uniwuerzburg.omod.MainKt")
}