package dev.byteforge

import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.model.*
import dev.byteforge.runtime.DynamicRunner
import dev.byteforge.runtime.RunResult
import kotlinx.serialization.json.JsonPrimitive

/**
 * Offline verification: hand-craft a ClassDefinition for Hello World,
 * compile via AsmCompiler, execute via DynamicRunner, assert output.
 * No API key needed.
 */
fun main() {
    println("=== Offline Test: AsmCompiler + DynamicRunner ===")
    println()

    // Hand-craft the equivalent of:
    //   public class Hello {
    //       public static void main(String[] args) {
    //           System.out.println("Hello from bytecode!");
    //       }
    //   }
    val classDef = ClassDefinition(
        name = "Hello",
        access = listOf(AccessFlag.PUBLIC, AccessFlag.SUPER),
        methods = listOf(
            MethodDefinition(
                access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                name = "main",
                descriptor = "([Ljava/lang/String;)V",
                instructions = listOf(
                    FieldInstruction(
                        op = "getstatic",
                        owner = "java/lang/System",
                        name = "out",
                        descriptor = "Ljava/io/PrintStream;",
                    ),
                    LdcInstruction(op = "ldc", value = JsonPrimitive("Hello from bytecode!")),
                    MethodInstruction(
                        op = "invokevirtual",
                        owner = "java/io/PrintStream",
                        name = "println",
                        descriptor = "(Ljava/lang/String;)V",
                    ),
                    SimpleInstruction(op = "return"),
                ),
            )
        ),
    )

    // Compile
    println("[1/2] Compiling hand-crafted ClassDefinition via ASM...")
    val bytecode = AsmCompiler.compile(classDef)
    println("      Generated ${bytecode.size} bytes")

    // Execute
    println("[2/2] Loading and executing...")
    val result = DynamicRunner.run("Hello", bytecode)

    when (result) {
        is RunResult.Success -> {
            val output = result.output
            println("      Output: \"${output.trimEnd()}\"")
            assert(output.trim() == "Hello from bytecode!") {
                "Expected 'Hello from bytecode!' but got '$output'"
            }
            println()
            println("PASS: Offline test succeeded!")
        }
        is RunResult.Failure -> {
            System.err.println("FAIL: ${result.error}")
            result.exception?.printStackTrace()
            System.exit(1)
        }
    }
}
