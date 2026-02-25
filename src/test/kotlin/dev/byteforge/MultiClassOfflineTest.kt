package dev.byteforge

import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.model.*
import dev.byteforge.runtime.DynamicRunner
import dev.byteforge.runtime.RunResult
import kotlinx.serialization.json.JsonPrimitive

/**
 * Offline test for multi-class support: Person + Main.
 * Person has name field, getName() method. Main creates Person and prints name.
 */
fun main() {
    println("=== Multi-Class Offline Test ===")
    println()

    // Person class with a name field and getName() method
    val personClass = ClassDefinition(
        name = "Person",
        access = listOf(AccessFlag.PUBLIC, AccessFlag.SUPER),
        fields = listOf(
            FieldDefinition(
                access = listOf(AccessFlag.PRIVATE),
                name = "name",
                descriptor = "Ljava/lang/String;",
            )
        ),
        methods = listOf(
            // Constructor: Person(String name)
            MethodDefinition(
                access = listOf(AccessFlag.PUBLIC),
                name = "<init>",
                descriptor = "(Ljava/lang/String;)V",
                instructions = listOf(
                    VarInstruction(op = "aload", varIndex = 0),
                    MethodInstruction(op = "invokespecial", owner = "java/lang/Object", name = "<init>", descriptor = "()V"),
                    VarInstruction(op = "aload", varIndex = 0),
                    VarInstruction(op = "aload", varIndex = 1),
                    FieldInstruction(op = "putfield", owner = "Person", name = "name", descriptor = "Ljava/lang/String;"),
                    SimpleInstruction(op = "return"),
                ),
            ),
            // getName(): String
            MethodDefinition(
                access = listOf(AccessFlag.PUBLIC),
                name = "getName",
                descriptor = "()Ljava/lang/String;",
                instructions = listOf(
                    VarInstruction(op = "aload", varIndex = 0),
                    FieldInstruction(op = "getfield", owner = "Person", name = "name", descriptor = "Ljava/lang/String;"),
                    SimpleInstruction(op = "areturn"),
                ),
            ),
        ),
    )

    // Main class that creates a Person and prints their name
    val mainClass = ClassDefinition(
        name = "Main",
        access = listOf(AccessFlag.PUBLIC, AccessFlag.SUPER),
        methods = listOf(
            MethodDefinition(
                access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                name = "main",
                descriptor = "([Ljava/lang/String;)V",
                instructions = listOf(
                    TypeInstruction(op = "new", type = "Person"),
                    SimpleInstruction(op = "dup"),
                    LdcInstruction(op = "ldc", value = JsonPrimitive("Alice")),
                    MethodInstruction(op = "invokespecial", owner = "Person", name = "<init>", descriptor = "(Ljava/lang/String;)V"),
                    VarInstruction(op = "astore", varIndex = 1),
                    FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                    VarInstruction(op = "aload", varIndex = 1),
                    MethodInstruction(op = "invokevirtual", owner = "Person", name = "getName", descriptor = "()Ljava/lang/String;"),
                    MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(Ljava/lang/String;)V"),
                    SimpleInstruction(op = "return"),
                ),
            ),
        ),
    )

    val program = ProgramDefinition(
        classes = listOf(personClass, mainClass),
        mainClass = "Main",
    )

    // Compile all classes
    println("[1/2] Compiling multi-class program...")
    val bytecodeMap = AsmCompiler.compileAll(program)
    bytecodeMap.forEach { (name, bytes) ->
        println("      $name: ${bytes.size} bytes")
    }

    // Execute
    println("[2/2] Loading and executing...")
    val result = DynamicRunner.run("Main", bytecodeMap)

    when (result) {
        is RunResult.Success -> {
            val output = result.output.trim()
            println("      Output: \"$output\"")
            assert(output == "Alice") { "Expected 'Alice' but got '$output'" }
            println()
            println("PASS: Multi-class offline test succeeded!")
        }
        is RunResult.Failure -> {
            System.err.println("FAIL: ${result.error}")
            result.exception?.printStackTrace()
            System.exit(1)
        }
    }
}
