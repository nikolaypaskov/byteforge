package dev.byteforge.compare

import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.compiler.CompilationException
import dev.byteforge.llm.ClaudeApiException
import dev.byteforge.llm.ClaudeClient
import kotlinx.coroutines.runBlocking
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

fun main(args: Array<String>) {
    val apiKey = System.getenv("ANTHROPIC_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("Error: ANTHROPIC_API_KEY environment variable is required")
        System.exit(1)
    }

    val prompt = if (args.isNotEmpty()) {
        args.joinToString(" ")
    } else {
        "Hello World program that prints 'Hello World!'"
    }

    println("=== ByteForge vs javac Comparison ===")
    println("Prompt: $prompt")
    println()

    // Step 1: Get bytecode from Claude via ByteForge pipeline
    println("[1/4] Generating bytecode via Claude API...")
    val classDef = try {
        runBlocking { ClaudeClient.generateBytecode(prompt, apiKey) }
    } catch (e: ClaudeApiException) {
        System.err.println("Claude API error: ${e.message}")
        System.exit(2)
        return
    }

    println("      Class: ${classDef.name}")
    println("      Instructions: ${classDef.methods.sumOf { it.instructions.size }}")
    println()

    // Step 2: Compile ByteForge output
    println("[2/4] Compiling ByteForge bytecode...")
    val byteforgeBytes = try {
        AsmCompiler.compile(classDef)
    } catch (e: CompilationException) {
        System.err.println("Compilation error: ${e.message}")
        System.exit(3)
        return
    }
    println("      Generated ${byteforgeBytes.size} bytes")
    println()

    // Step 3: Ask Claude for equivalent Java source
    println("[3/4] Requesting equivalent Java source...")
    val javaSource = try {
        runBlocking { requestJavaSource(prompt, apiKey) }
    } catch (e: Exception) {
        System.err.println("Failed to get Java source: ${e.message}")
        System.exit(2)
        return
    }
    println("      Got Java source (${javaSource.length} chars)")
    println()

    // Step 4: Compare
    println("[4/4] Comparing bytecode...")
    println()

    try {
        val result = ComparisonRunner.compare(javaSource, byteforgeBytes, classDef.name)

        println("=== javac output (javap -c) ===")
        println(result.javacJavap)
        println()
        println("=== ByteForge output (javap -c) ===")
        println(result.byteforgeJavap)
        println()
        println("=== Size Comparison ===")
        println("  javac:     ${result.javacSize} bytes")
        println("  ByteForge: ${result.byteforgeSize} bytes")
        println("  Diff:      ${String.format("%+.1f%%", result.sizeDiffPercent)}")
    } catch (e: Exception) {
        System.err.println("Comparison failed: ${e.message}")
        System.exit(4)
    }
}

/**
 * Ask Claude for plain Java source code (no tool use).
 */
private suspend fun requestJavaSource(prompt: String, apiKey: String): String {
    val json = Json { ignoreUnknownKeys = true }
    val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }
    try {
        val requestBody = buildJsonObject {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 4096)
            put("system", "You are a Java programmer. Write ONLY the Java source code, no explanations, no markdown fences. The class must be a top-level public class with a public static void main(String[] args) method.")
            put("messages", JsonArray(listOf(
                buildJsonObject {
                    put("role", "user")
                    put("content", "Write a Java program for: $prompt")
                }
            )))
        }
        val response = client.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), requestBody))
        }
        val responseText = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("API returned ${response.status}: $responseText")
        }
        val responseJson = json.parseToJsonElement(responseText).jsonObject
        val content = responseJson["content"]?.jsonArray
            ?: throw RuntimeException("Response missing content")
        val textBlock = content.firstOrNull { block ->
            block.jsonObject["type"]?.jsonPrimitive?.content == "text"
        }?.jsonObject ?: throw RuntimeException("No text block in response")
        var text = textBlock["text"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Text block missing 'text'")
        // Strip markdown fences if present
        text = text.replace(Regex("^```java\\s*\\n?"), "").replace(Regex("\\n?```\\s*$"), "").trim()
        return text
    } finally {
        client.close()
    }
}
