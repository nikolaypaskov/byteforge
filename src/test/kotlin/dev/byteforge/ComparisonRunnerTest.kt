package dev.byteforge

import dev.byteforge.compare.ComparisonRunner
import dev.byteforge.compare.ComparisonResult
import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.model.*
import kotlinx.serialization.json.JsonPrimitive

private var testNum = 0
private var passed = 0

private fun test(name: String, block: () -> Unit) {
    testNum++
    try {
        block()
        passed++
        println("  [$testNum] PASS: $name")
    } catch (e: Throwable) {
        println("  [$testNum] FAIL: $name — ${e.message}")
    }
}

private fun javacAvailable(): Boolean {
    return try {
        val p = ProcessBuilder("javac", "-version").redirectErrorStream(true).start()
        p.waitFor() == 0
    } catch (e: Exception) { false }
}

fun main() {
    println("=== ComparisonRunner Tests ===")
    println()

    if (!javacAvailable()) {
        println("SKIP: javac not available")
        return
    }

    // Build a simple Hello class with AsmCompiler equivalent to:
    //   public class Hello {
    //       public static void main(String[] args) {
    //           System.out.println("Hello!");
    //       }
    //   }
    val classDef = ClassDefinition(
        name = "Hello",
        methods = listOf(
            MethodDefinition(
                access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                name = "main",
                descriptor = "([Ljava/lang/String;)V",
                instructions = listOf(
                    FieldInstruction("getstatic", "java/lang/System", "out", "Ljava/io/PrintStream;"),
                    LdcInstruction("ldc", JsonPrimitive("Hello!")),
                    MethodInstruction("invokevirtual", "java/io/PrintStream", "println", "(Ljava/lang/String;)V"),
                    SimpleInstruction("return")
                )
            )
        )
    )
    val byteforgeBytes = AsmCompiler.compile(classDef)

    val javaSource = """
        public class Hello {
            public static void main(String[] args) {
                System.out.println("Hello!");
            }
        }
    """.trimIndent()

    val result = ComparisonRunner.compare(javaSource, byteforgeBytes, "Hello")

    // 1. Basic comparison produces valid result
    test("Basic comparison produces valid result") {
        assert(result is ComparisonResult) { "Expected ComparisonResult instance" }
        assert(result.javacJavap.isNotEmpty()) { "Expected non-empty javacJavap, got empty string" }
        assert(result.byteforgeJavap.isNotEmpty()) { "Expected non-empty byteforgeJavap, got empty string" }
    }

    // 2. Size difference calculation
    test("Size difference calculation") {
        assert(result.javacSize > 0) { "Expected javacSize > 0, got ${result.javacSize}" }
        assert(result.byteforgeSize > 0) { "Expected byteforgeSize > 0, got ${result.byteforgeSize}" }
        val expectedDiff = ((result.byteforgeSize - result.javacSize).toDouble() / result.javacSize) * 100.0
        assert(result.sizeDiffPercent.isFinite()) { "Expected finite sizeDiffPercent, got ${result.sizeDiffPercent}" }
        val tolerance = 0.001
        assert(Math.abs(result.sizeDiffPercent - expectedDiff) < tolerance) {
            "Expected sizeDiffPercent ~$expectedDiff, got ${result.sizeDiffPercent}"
        }
    }

    // 3. javap output contains method signatures
    test("javap output contains method signatures") {
        assert(result.javacJavap.contains("main")) { "Expected 'main' in javacJavap, got:\n${result.javacJavap}" }
        assert(result.byteforgeJavap.contains("main")) { "Expected 'main' in byteforgeJavap, got:\n${result.byteforgeJavap}" }
    }

    // 4. Both sizes are positive
    test("Both sizes are positive") {
        assert(result.javacSize > 0) { "Expected javacSize > 0, got ${result.javacSize}" }
        assert(result.byteforgeSize > 0) { "Expected byteforgeSize > 0, got ${result.byteforgeSize}" }
    }

    println()
    println("=== Results: $passed/$testNum passed ===")
    if (passed < testNum) {
        System.exit(1)
    }
}
