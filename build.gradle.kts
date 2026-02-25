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

tasks.register<JavaExec>("compare") {
    description = "Compare ByteForge bytecode with javac output"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.byteforge.compare.CompareMainKt")
}

tasks.register<JavaExec>("multiClassOfflineTest") {
    description = "Run multi-class offline test"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.byteforge.MultiClassOfflineTestKt")
}

tasks.register<JavaExec>("interactive") {
    description = "Run ByteForge in interactive REPL mode"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.byteforge.InteractiveMainKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("runWasm") {
    description = "Run ByteForge WASM pipeline"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.byteforge.WasmMainKt")
}

tasks.register<JavaExec>("bytecodeModelTest") {
    description = "Run bytecode model tests"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.byteforge.BytecodeModelTestKt")
}

tasks.register<JavaExec>("conversationManagerTest") {
    description = "Run conversation manager tests"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.byteforge.ConversationManagerTestKt")
}

tasks.register<JavaExec>("asmCompilerTest") {
    description = "Run ASM compiler tests"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.byteforge.AsmCompilerTestKt")
}

tasks.register<JavaExec>("dynamicRunnerTest") {
    description = "Run dynamic runner tests"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.byteforge.DynamicRunnerTestKt")
}

tasks.register<JavaExec>("watCompilerTest") {
    description = "Run WAT compiler tests"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.byteforge.WatCompilerTestKt")
}

tasks.register<JavaExec>("comparisonRunnerTest") {
    description = "Run comparison runner tests (requires javac)"
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.byteforge.ComparisonRunnerTestKt")
}

tasks.register("allOfflineTests") {
    description = "Run all offline test suites"
    group = "verification"
    dependsOn(
        "offlineTest",
        "multiClassOfflineTest",
        "bytecodeModelTest",
        "conversationManagerTest",
        "asmCompilerTest",
        "dynamicRunnerTest",
        "watCompilerTest",
    )
}
