package dev.byteforge

import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.compiler.CompilationException
import dev.byteforge.llm.ClaudeApiException
import dev.byteforge.llm.ClaudeClient
import dev.byteforge.runtime.DynamicRunner
import dev.byteforge.runtime.RunResult
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val apiKey = System.getenv("ANTHROPIC_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("Error: ANTHROPIC_API_KEY environment variable is required")
        System.err.println("Usage: ANTHROPIC_API_KEY=sk-ant-... ./gradlew run")
        System.exit(1)
    }

    val prompt = if (args.isNotEmpty()) {
        args.joinToString(" ")
    } else {
        "Hello World program that prints 'Hello from bytecode!'"
    }

    val saveClass = System.getenv("SAVE_CLASS")?.toBooleanStrictOrNull() == true

    println("=== Bytecode Agent POC ===")
    println("Prompt: $prompt")
    println()

    // Step 1: Ask Claude for bytecode
    println("[1/3] Generating bytecode via Claude API...")
    val classDef = try {
        runBlocking { ClaudeClient.generateBytecode(prompt, apiKey) }
    } catch (e: ClaudeApiException) {
        System.err.println("Claude API error: ${e.message}")
        System.exit(2)
        return // unreachable, satisfies compiler
    }

    println("      Class: ${classDef.name}")
    println("      Methods: ${classDef.methods.joinToString { "${it.name}${it.descriptor}" }}")
    println("      Total instructions: ${classDef.methods.sumOf { it.instructions.size }}")
    println()

    // Step 2: Compile to bytecode via ASM
    println("[2/3] Compiling to JVM bytecode via ASM...")
    val bytecode = try {
        AsmCompiler.compile(classDef)
    } catch (e: CompilationException) {
        System.err.println("Compilation error: ${e.message}")
        System.exit(3)
        return
    }
    println("      Generated ${bytecode.size} bytes")
    println()

    // Step 3: Load and execute
    println("[3/3] Loading and executing...")
    println("--- program output ---")
    val result = DynamicRunner.run(classDef.name, bytecode, saveClass)
    println("--- end output ---")
    println()

    when (result) {
        is RunResult.Success -> {
            print(result.output)
            println()
            println("Execution successful.")
        }
        is RunResult.Failure -> {
            System.err.println("Execution failed: ${result.error}")
            result.exception?.printStackTrace()
            System.exit(4)
        }
    }
}
