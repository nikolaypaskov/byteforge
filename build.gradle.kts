plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

group = "dev.byteforge"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // ASM bytecode generation
    implementation("org.ow2.asm:asm:9.7.1")

    // Ktor HTTP client
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

    // Kotlinx serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

application {
    mainClass.set("dev.byteforge.MainKt")
}

tasks.register<JavaExec>("offlineTest") {
    description = "Run offline test (no API key needed)"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.byteforge.OfflineTestKt")
}
