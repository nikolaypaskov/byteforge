package dev.byteforge

import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.compiler.CompilationException
import dev.byteforge.llm.ClaudeApiException
import dev.byteforge.llm.ClaudeClient
import dev.byteforge.llm.ConversationManager
import dev.byteforge.runtime.DynamicRunner
import dev.byteforge.runtime.RunResult
import kotlinx.coroutines.runBlocking

/**
 * Interactive REPL for evolving programs through conversation.
 * Each turn: prompt -> generate -> compile -> run -> show diff -> next prompt.
 */
fun main() {
    val apiKey = System.getenv("ANTHROPIC_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("Error: ANTHROPIC_API_KEY environment variable is required")
        System.exit(1)
    }

    val maxRetries = System.getenv("MAX_RETRIES")?.toIntOrNull() ?: 3
    val saveClass = System.getenv("SAVE_CLASS")?.toBooleanStrictOrNull() == true
    val conversation = ConversationManager()
    var turnNumber = 0
    var lastInstructionCount = 0
    var lastMethodCount = 0

    println("=== ByteForge Interactive Mode ===")
    println("Type your program description or modification. Type 'quit' to exit.")
    println()

    while (true) {
        turnNumber++
        print("byteforge[$turnNumber]> ")
        System.out.flush()
        val input = readlnOrNull()?.trim() ?: break
        if (input.equals("quit", ignoreCase = true) || input.equals("exit", ignoreCase = true)) {
            println("Goodbye!")
            break
        }
        if (input.isBlank()) continue

        conversation.addUserMessage(input)

        var success = false
        for (attempt in 1..maxRetries) {
            if (attempt > 1) {
                println("  [retry $attempt/$maxRetries] Asking Claude to fix...")
            }

            println("  Generating bytecode...")
            val result = try {
                runBlocking {
                    ClaudeClient.sendMessages(
                        conversation.getMessages(),
                        apiKey,
                        systemPrompt = ClaudeClient.interactiveSystemPrompt,
                    )
                }
            } catch (e: ClaudeApiException) {
                System.err.println("  API error: ${e.message}")
                break
            }

            conversation.addAssistantResponse(result.assistantContent)

            val classDef = result.classDefinition
            val instructionCount = classDef.methods.sumOf { it.instructions.size }
            val methodCount = classDef.methods.size

            println("  Class: ${classDef.name} | Methods: $methodCount | Instructions: $instructionCount")

            // Show diff from previous turn
            if (turnNumber > 1 || attempt > 1) {
                val instrDiff = instructionCount - lastInstructionCount
                val methodDiff = methodCount - lastMethodCount
                val instrSign = if (instrDiff >= 0) "+" else ""
                val methodSign = if (methodDiff >= 0) "+" else ""
                println("  Delta: methods ${methodSign}$methodDiff, instructions ${instrSign}$instrDiff")
            }

            // Compile
            println("  Compiling...")
            val bytecode = try {
                AsmCompiler.compile(classDef)
            } catch (e: CompilationException) {
                val errorMsg = "Compilation error: ${e.message}"
                System.err.println("  $errorMsg")
                if (attempt < maxRetries) {
                    conversation.addToolResult(result.toolUseId, ClaudeClient.buildErrorFeedback(errorMsg), isError = true)
                    continue
                }
                break
            }

            // Execute
            println("  Running... (${bytecode.size} bytes)")
            println("  --- output ---")
            val runResult = DynamicRunner.run(classDef.name, bytecode, saveClass)

            when (runResult) {
                is RunResult.Success -> {
                    print("  ${runResult.output.replace("\n", "\n  ")}")
                    println()
                    println("  --- end ---")
                    lastInstructionCount = instructionCount
                    lastMethodCount = methodCount
                    // Add success tool result so conversation continues naturally
                    conversation.addToolResult(result.toolUseId, "Program compiled and executed successfully. Output:\n${runResult.output}")
                    success = true
                    break
                }
                is RunResult.Failure -> {
                    println("  --- end ---")
                    val errorMsg = "Execution failed: ${runResult.error}"
                    System.err.println("  $errorMsg")
                    if (attempt < maxRetries) {
                        conversation.addToolResult(result.toolUseId, ClaudeClient.buildErrorFeedback(errorMsg), isError = true)
                        continue
                    }
                    break
                }
            }
        }

        if (!success) {
            println("  Failed after $maxRetries attempts. You can try rephrasing.")
        }
        println()
    }
}
