import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.10"
    application
}

group = "de.uniwuerzburg"
version = "0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.locationtech.jts:jts:1.18.2")
    implementation("com.graphhopper:graphhopper-core:4.0")
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