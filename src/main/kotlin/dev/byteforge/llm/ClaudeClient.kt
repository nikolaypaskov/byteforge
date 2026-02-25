package dev.byteforge.llm

import dev.byteforge.model.ClassDefinition
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

class ClaudeApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object ClaudeClient {

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"
    private const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
    private const val MAX_TOKENS = 16384

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The system prompt instructs Claude on the exact JVM bytecode format it should emit.
     */
    private val systemPrompt = """
        You are a JVM bytecode compiler. You translate natural-language program descriptions into
        JVM bytecode instructions via the emit_bytecode tool.

        Rules:
        - Use JVM internal names: "java/lang/Object", "java/io/PrintStream", "java/lang/System"
        - Use JVM type descriptors: "Ljava/lang/String;", "V", "I", "([Ljava/lang/String;)V"
        - DO NOT emit a <init> constructor — one is auto-generated
        - Always include a "main" method with descriptor "([Ljava/lang/String;)V" and access ["public", "static"]
        - The class name should be simple (e.g. "Hello") — no package prefix
        - Supported opcodes: ldc, getstatic, putstatic, getfield, putfield,
          invokevirtual, invokestatic, invokespecial, invokeinterface,
          iload, istore, aload, astore, fload, fstore, dload, dstore, lload, lstore,
          new, anewarray, checkcast, instanceof,
          bipush, sipush, newarray,
          return, areturn, ireturn, lreturn, freturn, dreturn,
          iadd, isub, imul, idiv, irem, ineg,
          ladd, lsub, lmul, ldiv,
          fadd, fsub, fmul, fdiv,
          dadd, dsub, dmul, ddiv,
          dup, pop, swap, aconst_null,
          iconst_m1, iconst_0, iconst_1, iconst_2, iconst_3, iconst_4, iconst_5,
          lconst_0, lconst_1, fconst_0, fconst_1, fconst_2, dconst_0, dconst_1,
          ifeq, ifne, iflt, ifge, ifgt, ifle,
          if_icmpeq, if_icmpne, if_icmplt, if_icmpge, if_icmpgt, if_icmple,
          if_acmpeq, if_acmpne, ifnull, ifnonnull, goto,
          label (pseudo-instruction to mark jump targets),
          aaload, aastore, iaload, iastore, arraylength, athrow,
          i2l, i2f, i2d, l2i, f2i, d2i
        - For jump/branch instructions use: {"op":"goto","label":"loop_start"}
        - To define label targets use: {"op":"label","name":"loop_start"}
        - Stack sizes and frames are computed automatically — do not worry about them
        - Always call the emit_bytecode tool. Never respond with text explanations.
    """.trimIndent()

    /**
     * JSON Schema for the emit_bytecode tool, describing the ClassDefinition structure.
     */
    private val emitBytecodeTool = buildJsonObject {
        put("name", "emit_bytecode")
        put("description", "Emit JVM bytecode class definition as structured data")
        put("input_schema", buildJsonObject {
            put("type", "object")
            put("required", JsonArray(listOf("name", "methods").map { JsonPrimitive(it) }))
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Class name in JVM internal format (e.g. 'Hello')")
                })
                put("superName", buildJsonObject {
                    put("type", "string")
                    put("description", "Superclass in JVM internal format")
                    put("default", "java/lang/Object")
                })
                put("interfaces", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("access", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf("public", "private", "protected", "static", "final", "super", "abstract", "interface").map { JsonPrimitive(it) }))
                    })
                })
                put("fields", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", JsonArray(listOf("access", "name", "descriptor").map { JsonPrimitive(it) }))
                        put("properties", buildJsonObject {
                            put("access", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("name", buildJsonObject { put("type", "string") })
                            put("descriptor", buildJsonObject { put("type", "string") })
                        })
                    })
                })
                put("methods", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", JsonArray(listOf("access", "name", "descriptor", "instructions").map { JsonPrimitive(it) }))
                        put("properties", buildJsonObject {
                            put("access", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "string") })
                            })
                            put("name", buildJsonObject { put("type", "string") })
                            put("descriptor", buildJsonObject { put("type", "string") })
                            put("instructions", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("required", JsonArray(listOf(JsonPrimitive("op"))))
                                    put("properties", buildJsonObject {
                                        put("op", buildJsonObject { put("type", "string") })
                                        put("value", buildJsonObject {
                                            put("description", "Constant value for ldc")
                                        })
                                        put("owner", buildJsonObject { put("type", "string") })
                                        put("name", buildJsonObject { put("type", "string") })
                                        put("descriptor", buildJsonObject { put("type", "string") })
                                        put("isInterface", buildJsonObject { put("type", "boolean") })
                                        put("var", buildJsonObject { put("type", "integer") })
                                        put("type", buildJsonObject { put("type", "string") })
                                        put("operand", buildJsonObject { put("type", "integer") })
                                        put("label", buildJsonObject {
                                            put("type", "string")
                                            put("description", "Target label name for jump instructions")
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        })
    }

    /**
     * Sends a prompt to Claude and returns a ClassDefinition from the tool_use response.
     */
    suspend fun generateBytecode(prompt: String, apiKey: String, model: String = DEFAULT_MODEL): ClassDefinition {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }

        try {
            val requestBody = buildJsonObject {
                put("model", model)
                put("max_tokens", MAX_TOKENS)
                put("system", systemPrompt)
                put("tools", JsonArray(listOf(emitBytecodeTool)))
                put("tool_choice", buildJsonObject {
                    put("type", "tool")
                    put("name", "emit_bytecode")
                })
                put("messages", JsonArray(listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    }
                )))
            }

            val response = client.post(API_URL) {
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), requestBody))
            }

            val responseText = response.bodyAsText()

            if (response.status != HttpStatusCode.OK) {
                throw ClaudeApiException("API returned ${response.status}: $responseText")
            }

            val responseJson = json.parseToJsonElement(responseText).jsonObject
            val content = responseJson["content"]?.jsonArray
                ?: throw ClaudeApiException("Response missing 'content' field")

            // Find the tool_use content block
            val toolUseBlock = content.firstOrNull { block ->
                block.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
            }?.jsonObject ?: throw ClaudeApiException("No tool_use block in response")

            val input = toolUseBlock["input"]?.jsonObject
                ?: throw ClaudeApiException("tool_use block missing 'input'")

            return json.decodeFromJsonElement(ClassDefinition.serializer(), input)
        } catch (e: ClaudeApiException) {
            throw e
        } catch (e: Exception) {
            throw ClaudeApiException("Failed to communicate with Claude API: ${e.message}", e)
        } finally {
            client.close()
        }
    }
}
