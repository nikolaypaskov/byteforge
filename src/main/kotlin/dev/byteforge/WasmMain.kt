package dev.byteforge

import dev.byteforge.compiler.WatCompiler
import dev.byteforge.llm.ClaudeApiException
import dev.byteforge.llm.ClaudeClient
import dev.byteforge.runtime.WasmRunner
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val apiKey = System.getenv("ANTHROPIC_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("Error: ANTHROPIC_API_KEY environment variable is required")
        System.err.println("Usage: ANTHROPIC_API_KEY=sk-ant-... ./gradlew runWasm")
        System.exit(1)
    }

    val prompt = if (args.isNotEmpty()) {
        args.joinToString(" ")
    } else {
        "A function that computes fibonacci(10) and returns the result"
    }

    println("=== ByteForge WASM Pipeline ===")
    println("Prompt: $prompt")
    println()

    // Step 1: Ask Claude for WASM bytecode
    println("[1/3] Generating WASM module via Claude API...")
    val wasmModule = try {
        runBlocking { ClaudeClient.generateWasm(prompt, apiKey) }
    } catch (e: ClaudeApiException) {
        System.err.println("Claude API error: ${e.message}")
        System.exit(2)
        return
    }

    println("      Functions: ${wasmModule.functions.joinToString { it.name }}")
    println("      Total instructions: ${wasmModule.functions.sumOf { it.instructions.size }}")
    println("      Imports: ${wasmModule.imports.size}")
    println("      Exports: ${wasmModule.exports.size}")
    println("      Data segments: ${wasmModule.data.size}")
    println()

    // Step 2: Compile to WASM binary via WAT
    println("[2/3] Compiling to WASM binary via wat2wasm...")
    val wat = WatCompiler.toWat(wasmModule)
    println("      WAT output:")
    wat.lines().forEach { println("        $it") }
    println()

    val wasmBytes = try {
        WatCompiler.compile(wasmModule)
    } catch (e: Exception) {
        System.err.println("WASM compilation error: ${e.message}")
        System.exit(3)
        return
    }
    println("      Generated ${wasmBytes.size} bytes")
    println()

    // Step 3: Execute
    println("[3/3] Executing WASM module...")
    println("--- program output ---")
    try {
        val result = WasmRunner.run(wasmBytes)
        print(result.output)
        println("--- end output ---")
        println()
        println("Execution successful (runtime: ${result.runtime}, exit code: ${result.exitCode}).")
    } catch (e: Exception) {
        println("--- end output ---")
        System.err.println("Execution failed: ${e.message}")
        System.exit(4)
    }
}
