package dev.byteforge.llm

import dev.byteforge.model.ClassDefinition
import dev.byteforge.model.ProgramDefinition
import dev.byteforge.model.WasmModule
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

data class GenerationResult(
    val classDefinition: ClassDefinition,
    val assistantContent: JsonArray,
    val toolUseId: String,
)

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

    private val multiClassSystemPrompt = """
        You are a JVM bytecode compiler. You translate natural-language program descriptions into
        JVM bytecode instructions via the emit_bytecode_multi tool.

        You can emit MULTIPLE cooperating classes in a single response. Each class should be a complete
        ClassDefinition. Specify which class contains the main() entry point via the mainClass field.

        Rules:
        - Use JVM internal names: "java/lang/Object", "java/io/PrintStream", "java/lang/System"
        - Use JVM type descriptors: "Ljava/lang/String;", "V", "I", "([Ljava/lang/String;)V"
        - DO NOT emit a <init> constructor — one is auto-generated for each class
        - The main class must include a "main" method with descriptor "([Ljava/lang/String;)V" and access ["public", "static"]
        - Class names should be simple (e.g. "Person", "Main") — no package prefix
        - When one class references another, use the class name directly as the type (e.g. "Person" not "Ljava/lang/Person;")
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
        - Always call the emit_bytecode_multi tool. Never respond with text explanations.
    """.trimIndent()

    val interactiveSystemPrompt = """
        You are a JVM bytecode compiler in interactive mode. You translate natural-language program
        descriptions into JVM bytecode instructions via the emit_bytecode tool.

        You are in a CONVERSATION. The user may ask you to modify, extend, or fix a previous program.
        When modifying, emit the COMPLETE updated class — not just the changes.

        Previous tool results show you what happened with your last emission (success or error).
        Use this context to maintain and evolve the program across turns.

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
     * The single-class schema, used by both emit_bytecode and as item schema for emit_bytecode_multi.
     */
    private val classInputSchema = buildJsonObject {
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
    }

    /**
     * JSON Schema for the emit_bytecode tool, describing the ClassDefinition structure.
     */
    private val emitBytecodeTool = buildJsonObject {
        put("name", "emit_bytecode")
        put("description", "Emit JVM bytecode class definition as structured data")
        put("input_schema", classInputSchema)
    }

    private val emitBytecodeMultiTool = buildJsonObject {
        put("name", "emit_bytecode_multi")
        put("description", "Emit multiple cooperating JVM bytecode class definitions")
        put("input_schema", buildJsonObject {
            put("type", "object")
            put("required", JsonArray(listOf("classes", "mainClass").map { JsonPrimitive(it) }))
            put("properties", buildJsonObject {
                put("classes", buildJsonObject {
                    put("type", "array")
                    put("description", "List of class definitions")
                    put("items", classInputSchema)
                })
                put("mainClass", buildJsonObject {
                    put("type", "string")
                    put("description", "Name of the class containing the main() entry point")
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

    /**
     * Sends a list of messages to Claude and returns a GenerationResult with the class definition,
     * assistant content, and tool_use ID for conversation continuity.
     */
    suspend fun sendMessages(
        messages: List<JsonObject>,
        apiKey: String,
        systemPrompt: String = this.systemPrompt,
        tools: List<JsonObject> = listOf(emitBytecodeTool),
        toolName: String = "emit_bytecode",
        model: String = DEFAULT_MODEL,
    ): GenerationResult {
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
                put("model", model)
                put("max_tokens", MAX_TOKENS)
                put("system", systemPrompt)
                put("tools", JsonArray(tools))
                put("tool_choice", buildJsonObject {
                    put("type", "tool")
                    put("name", toolName)
                })
                put("messages", JsonArray(messages))
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
            val toolUseBlock = content.firstOrNull { block ->
                block.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
            }?.jsonObject ?: throw ClaudeApiException("No tool_use block in response")
            val toolUseId = toolUseBlock["id"]?.jsonPrimitive?.content
                ?: throw ClaudeApiException("tool_use block missing 'id'")
            val input = toolUseBlock["input"]?.jsonObject
                ?: throw ClaudeApiException("tool_use block missing 'input'")
            val classDef = json.decodeFromJsonElement(ClassDefinition.serializer(), input)
            return GenerationResult(classDef, content, toolUseId)
        } catch (e: ClaudeApiException) {
            throw e
        } catch (e: Exception) {
            throw ClaudeApiException("Failed to communicate with Claude API: ${e.message}", e)
        } finally {
            client.close()
        }
    }

    /**
     * Sends a prompt to Claude for multi-class generation and returns a ProgramDefinition.
     */
    suspend fun generateMultiClassBytecode(prompt: String, apiKey: String, model: String = DEFAULT_MODEL): ProgramDefinition {
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
                put("model", model)
                put("max_tokens", MAX_TOKENS)
                put("system", multiClassSystemPrompt)
                put("tools", JsonArray(listOf(emitBytecodeMultiTool)))
                put("tool_choice", buildJsonObject {
                    put("type", "tool")
                    put("name", "emit_bytecode_multi")
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
            val toolUseBlock = content.firstOrNull { block ->
                block.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
            }?.jsonObject ?: throw ClaudeApiException("No tool_use block in response")
            val input = toolUseBlock["input"]?.jsonObject
                ?: throw ClaudeApiException("tool_use block missing 'input'")
            return json.decodeFromJsonElement(ProgramDefinition.serializer(), input)
        } catch (e: ClaudeApiException) {
            throw e
        } catch (e: Exception) {
            throw ClaudeApiException("Failed to communicate with Claude API: ${e.message}", e)
        } finally {
            client.close()
        }
    }

    fun buildErrorFeedback(error: String): String {
        return "The previous bytecode failed with the following error. Please fix and re-emit:\n\n$error"
    }

    // ── WASM target ──────────────────────────────────────────────────────

    private val wasmSystemPrompt = """
        You are a WebAssembly compiler. You translate natural-language program descriptions into
        WebAssembly modules via the emit_wasm tool.

        Rules:
        - Generate a complete WASM module with functions, imports, exports, and data segments as needed
        - For programs that print output, use WASI fd_write via import from "wasi_unstable"
        - String data should be placed in data segments with explicit memory offsets
        - Export the main function as "_start" for WASI compatibility
        - Supported value types: i32, i64, f32, f64
        - Common instructions: i32.const, i32.add, i32.sub, i32.mul, i32.div_s, i32.rem_s,
          i32.eq, i32.ne, i32.lt_s, i32.gt_s, i32.le_s, i32.ge_s,
          i32.and, i32.or, i32.xor, i32.shl, i32.shr_s, i32.shr_u,
          i64.const, i64.add, i64.sub, i64.mul, i64.div_s,
          f32.const, f32.add, f32.sub, f32.mul, f32.div,
          f64.const, f64.add, f64.sub, f64.mul, f64.div,
          local.get, local.set, local.tee,
          global.get, global.set,
          i32.load, i32.store, i32.load8_s, i32.load8_u, i32.store8,
          memory.size, memory.grow,
          call, call_indirect,
          block, loop, br, br_if, br_table, end, if, else, then,
          return, drop, select, nop, unreachable
        - For WASI fd_write to print strings:
          1. Store string data in a data segment
          2. Set up an iov (pointer + length) in memory
          3. Call fd_write(fd=1, iovs_ptr, iovs_len=1, nwritten_ptr)
        - Memory is measured in pages (64KB each). Usually 1 page is sufficient.
        - Always call the emit_wasm tool. Never respond with text explanations.
    """.trimIndent()

    private val emitWasmTool = buildJsonObject {
        put("name", "emit_wasm")
        put("description", "Emit a WebAssembly module definition as structured data")
        put("input_schema", buildJsonObject {
            put("type", "object")
            put("required", JsonArray(listOf("functions").map { JsonPrimitive(it) }))
            put("properties", buildJsonObject {
                put("imports", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", JsonArray(listOf("module", "name", "kind").map { JsonPrimitive(it) }))
                        put("properties", buildJsonObject {
                            put("module", buildJsonObject { put("type", "string") })
                            put("name", buildJsonObject { put("type", "string") })
                            put("kind", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("func", "memory").map { JsonPrimitive(it) })) })
                            put("params", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                            put("results", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                            put("funcName", buildJsonObject { put("type", "string") })
                            put("memoryMin", buildJsonObject { put("type", "integer") })
                            put("memoryMax", buildJsonObject { put("type", "integer") })
                        })
                    })
                })
                put("exports", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", JsonArray(listOf("name", "kind", "ref").map { JsonPrimitive(it) }))
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("kind", buildJsonObject { put("type", "string") })
                            put("ref", buildJsonObject { put("type", "string") })
                        })
                    })
                })
                put("functions", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", JsonArray(listOf("name", "instructions").map { JsonPrimitive(it) }))
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                            put("params", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                            put("results", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                            put("locals", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                            put("instructions", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("required", JsonArray(listOf(JsonPrimitive("op"))))
                                    put("properties", buildJsonObject {
                                        put("op", buildJsonObject { put("type", "string") })
                                        put("value", buildJsonObject { put("type", "string") })
                                    })
                                })
                            })
                        })
                    })
                })
                put("data", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("required", JsonArray(listOf("offset", "data").map { JsonPrimitive(it) }))
                        put("properties", buildJsonObject {
                            put("offset", buildJsonObject { put("type", "integer") })
                            put("data", buildJsonObject { put("type", "string") })
                        })
                    })
                })
                put("memoryPages", buildJsonObject { put("type", "integer"); put("default", 1) })
            })
        })
    }

    suspend fun generateWasm(prompt: String, apiKey: String, model: String = DEFAULT_MODEL): WasmModule {
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
                put("model", model)
                put("max_tokens", MAX_TOKENS)
                put("system", wasmSystemPrompt)
                put("tools", JsonArray(listOf(emitWasmTool)))
                put("tool_choice", buildJsonObject {
                    put("type", "tool")
                    put("name", "emit_wasm")
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
            val toolUseBlock = content.firstOrNull { block ->
                block.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
            }?.jsonObject ?: throw ClaudeApiException("No tool_use block in response")
            val input = toolUseBlock["input"]?.jsonObject
                ?: throw ClaudeApiException("tool_use block missing 'input'")
            return json.decodeFromJsonElement(WasmModule.serializer(), input)
        } catch (e: ClaudeApiException) {
            throw e
        } catch (e: Exception) {
            throw ClaudeApiException("Failed to communicate with Claude API: ${e.message}", e)
        } finally {
            client.close()
        }
    }
}
