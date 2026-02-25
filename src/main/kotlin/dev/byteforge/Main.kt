package dev.byteforge

import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.compiler.CompilationException
import dev.byteforge.llm.ClaudeApiException
import dev.byteforge.llm.ClaudeClient
import dev.byteforge.llm.ConversationManager
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

    val isMulti = args.contains("--multi")
    val filteredArgs = args.filter { it != "--multi" }
    val prompt = if (filteredArgs.isNotEmpty()) {
        filteredArgs.joinToString(" ")
    } else {
        if (isMulti) "A Person class with name/age fields and a Main class that creates a Person and prints their info"
        else "Hello World program that prints 'Hello from bytecode!'"
    }

    val saveClass = System.getenv("SAVE_CLASS")?.toBooleanStrictOrNull() == true
    val maxRetries = System.getenv("MAX_RETRIES")?.toIntOrNull() ?: 3

    println("=== Bytecode Agent POC ===")
    println("Prompt: $prompt")
    if (isMulti) println("Mode: Multi-class")
    println()

    if (isMulti) {
        runMultiClass(prompt, apiKey, saveClass, maxRetries)
    } else {
        runSingleClass(prompt, apiKey, saveClass, maxRetries)
    }
}

private fun runSingleClass(prompt: String, apiKey: String, saveClass: Boolean, maxRetries: Int) {
    val conversation = ConversationManager()
    conversation.addUserMessage(prompt)

    for (attempt in 1..maxRetries) {
        if (attempt > 1) {
            println("[retry $attempt/$maxRetries] Asking Claude to fix the error...")
        }

        // Step 1: Ask Claude for bytecode
        println("[1/3] Generating bytecode via Claude API...")
        val result = try {
            runBlocking { ClaudeClient.sendMessages(conversation.getMessages(), apiKey) }
        } catch (e: ClaudeApiException) {
            System.err.println("Claude API error: ${e.message}")
            System.exit(2)
            return
        }

        conversation.addAssistantResponse(result.assistantContent)

        println("      Class: ${result.classDefinition.name}")
        println("      Methods: ${result.classDefinition.methods.joinToString { "${it.name}${it.descriptor}" }}")
        println("      Total instructions: ${result.classDefinition.methods.sumOf { it.instructions.size }}")
        println()

        // Step 2: Compile
        println("[2/3] Compiling to JVM bytecode via ASM...")
        val bytecode = try {
            AsmCompiler.compile(result.classDefinition)
        } catch (e: CompilationException) {
            val errorMsg = "Compilation error: ${e.message}"
            System.err.println(errorMsg)
            if (attempt < maxRetries) {
                conversation.addToolResult(result.toolUseId, ClaudeClient.buildErrorFeedback(errorMsg), isError = true)
                println()
                continue
            }
            System.exit(3)
            return
        }
        println("      Generated ${bytecode.size} bytes")
        println()

        // Step 3: Execute
        println("[3/3] Loading and executing...")
        println("--- program output ---")
        val runResult = DynamicRunner.run(result.classDefinition.name, bytecode, saveClass)
        println("--- end output ---")
        println()

        when (runResult) {
            is RunResult.Success -> {
                print(runResult.output)
                println()
                println("Execution successful.")
                return
            }
            is RunResult.Failure -> {
                val errorMsg = "Execution failed: ${runResult.error}"
                System.err.println(errorMsg)
                if (attempt < maxRetries) {
                    conversation.addToolResult(result.toolUseId, ClaudeClient.buildErrorFeedback(errorMsg), isError = true)
                    println()
                    continue
                }
                System.exit(4)
                return
            }
        }
    }
}

private fun runMultiClass(prompt: String, apiKey: String, saveClass: Boolean, maxRetries: Int) {
    for (attempt in 1..maxRetries) {
        if (attempt > 1) {
            println("[retry $attempt/$maxRetries] Retrying multi-class generation...")
        }

        // Step 1: Ask Claude for multi-class bytecode
        println("[1/3] Generating multi-class bytecode via Claude API...")
        val program = try {
            runBlocking { ClaudeClient.generateMultiClassBytecode(prompt, apiKey) }
        } catch (e: ClaudeApiException) {
            System.err.println("Claude API error: ${e.message}")
            if (attempt < maxRetries) {
                println()
                continue
            }
            System.exit(2)
            return
        }

        println("      Main class: ${program.mainClass}")
        println("      Classes: ${program.classes.joinToString { it.name }}")
        program.classes.forEach { cls ->
            println("        ${cls.name}: ${cls.methods.size} methods, ${cls.methods.sumOf { it.instructions.size }} instructions")
        }
        println()

        // Step 2: Compile all classes
        println("[2/3] Compiling all classes to JVM bytecode via ASM...")
        val bytecodeMap = try {
            AsmCompiler.compileAll(program)
        } catch (e: CompilationException) {
            val errorMsg = "Compilation error: ${e.message}"
            System.err.println(errorMsg)
            if (attempt < maxRetries) {
                println()
                continue
            }
            System.exit(3)
            return
        }
        bytecodeMap.forEach { (name, bytes) ->
            println("      $name: ${bytes.size} bytes")
        }
        println()

        // Step 3: Execute
        println("[3/3] Loading and executing...")
        println("--- program output ---")
        val runResult = DynamicRunner.run(program.mainClass, bytecodeMap, saveClass)
        println("--- end output ---")
        println()

        when (runResult) {
            is RunResult.Success -> {
                print(runResult.output)
                println()
                println("Execution successful.")
                return
            }
            is RunResult.Failure -> {
                val errorMsg = "Execution failed: ${runResult.error}"
                System.err.println(errorMsg)
                if (attempt < maxRetries) {
                    println()
                    continue
                }
                System.exit(4)
                return
            }
        }
    }
}
