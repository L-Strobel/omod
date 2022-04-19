import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.10"
    kotlin("plugin.serialization") version "1.5.10"
    application
}

group = "de.uniwuerzburg"
version = "0.1"

repositories {
    maven {
        url = uri("https://repo.osgeo.org/repository/release/")
    }
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    implementation("org.locationtech.jts:jts-core:1.18.2")
    implementation("org.apache.commons:commons-math3:3.6.1")
    implementation("org.geotools:gt-shapefile:26.4")
    implementation("org.geotools:gt-epsg-hsql:26.4")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}