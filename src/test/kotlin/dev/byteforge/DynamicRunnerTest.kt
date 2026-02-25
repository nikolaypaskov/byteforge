package dev.byteforge

import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.model.*
import dev.byteforge.runtime.BytecodeClassLoader
import dev.byteforge.runtime.DynamicRunner
import dev.byteforge.runtime.RunResult
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

fun main() {
    println("=== DynamicRunnerTest ===")
    println()

    // ---------------------------------------------------------------------------
    // 1. Single-class with stdout capture — Hello World
    // ---------------------------------------------------------------------------
    test("Single-class Hello World captures stdout") {
        val classDef = ClassDefinition(
            name = "HelloRunner",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        LdcInstruction(op = "ldc", value = JsonPrimitive("Hello from bytecode!")),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(Ljava/lang/String;)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("HelloRunner", bytecode)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output.trim()
        assert(output == "Hello from bytecode!") { "Expected 'Hello from bytecode!' but got '$output'" }
    }

    // ---------------------------------------------------------------------------
    // 2. Multi-class with cross-references — Calculator + Main
    // ---------------------------------------------------------------------------
    test("Multi-class cross-reference: Calculator.add(3, 4) = 7") {
        // Calculator with static add(int, int) method
        val calculatorClass = ClassDefinition(
            name = "Calculator",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "add",
                    descriptor = "(II)I",
                    instructions = listOf(
                        VarInstruction(op = "iload", varIndex = 0),
                        VarInstruction(op = "iload", varIndex = 1),
                        SimpleInstruction(op = "iadd"),
                        SimpleInstruction(op = "ireturn"),
                    ),
                )
            ),
        )
        // Main that calls Calculator.add(3, 4) and prints result
        val mainClass = ClassDefinition(
            name = "CalcMain",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        SimpleInstruction(op = "iconst_3"),
                        SimpleInstruction(op = "iconst_4"),
                        MethodInstruction(op = "invokestatic", owner = "Calculator", name = "add", descriptor = "(II)I"),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(I)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val program = ProgramDefinition(classes = listOf(calculatorClass, mainClass), mainClass = "CalcMain")
        val bytecodeMap = AsmCompiler.compileAll(program)
        val result = DynamicRunner.run("CalcMain", bytecodeMap)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output.trim()
        assert(output == "7") { "Expected '7' but got '$output'" }
    }

    // ---------------------------------------------------------------------------
    // 3. No main method yields RunResult.Failure
    // ---------------------------------------------------------------------------
    test("No main method returns RunResult.Failure") {
        val classDef = ClassDefinition(
            name = "NoMain",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "helper",
                    descriptor = "()V",
                    instructions = listOf(SimpleInstruction(op = "return")),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("NoMain", bytecode)
        assert(result is RunResult.Failure) { "Expected Failure but got $result" }
        val error = (result as RunResult.Failure).error.lowercase()
        assert(error.contains("no main")) { "Error should mention 'no main', got: '${result.error}'" }
    }

    // ---------------------------------------------------------------------------
    // 4. Execution throws exception (divide by zero) yields Failure
    // ---------------------------------------------------------------------------
    test("Divide by zero returns RunResult.Failure") {
        val classDef = ClassDefinition(
            name = "DivZero",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        SimpleInstruction(op = "iconst_1"),
                        SimpleInstruction(op = "iconst_0"),
                        SimpleInstruction(op = "idiv"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("DivZero", bytecode)
        assert(result is RunResult.Failure) { "Expected Failure but got $result" }
    }

    // ---------------------------------------------------------------------------
    // 5. BytecodeClassLoader: ClassNotFoundException for missing class
    // ---------------------------------------------------------------------------
    test("BytecodeClassLoader throws ClassNotFoundException for unknown class") {
        val loader = BytecodeClassLoader(emptyMap())
        var caught = false
        try {
            loader.loadClass("NonExistent")
        } catch (e: ClassNotFoundException) {
            caught = true
        }
        assert(caught) { "Expected ClassNotFoundException to be thrown" }
    }

    // ---------------------------------------------------------------------------
    // 6. Multiple prints captured correctly
    // ---------------------------------------------------------------------------
    test("Multiple prints are all captured in output") {
        val classDef = ClassDefinition(
            name = "MultiPrint",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        // println("line1")
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        LdcInstruction(op = "ldc", value = JsonPrimitive("line1")),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(Ljava/lang/String;)V"),
                        // println("line2")
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        LdcInstruction(op = "ldc", value = JsonPrimitive("line2")),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(Ljava/lang/String;)V"),
                        // println("line3")
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        LdcInstruction(op = "ldc", value = JsonPrimitive("line3")),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(Ljava/lang/String;)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("MultiPrint", bytecode)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output
        assert(output.contains("line1")) { "Output should contain 'line1'" }
        assert(output.contains("line2")) { "Output should contain 'line2'" }
        assert(output.contains("line3")) { "Output should contain 'line3'" }
        val lines = output.trim().split("\n")
        assert(lines.size == 3) { "Expected 3 lines but got ${lines.size}" }
    }

    // ---------------------------------------------------------------------------
    // 7. Multi-class: missing dependency yields Failure
    // ---------------------------------------------------------------------------
    test("Multi-class with missing dependency returns RunResult.Failure") {
        // Main references "Missing" class but we only provide Main's bytecode
        val mainClass = ClassDefinition(
            name = "MainMissingDep",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        TypeInstruction(op = "new", type = "Missing"),
                        SimpleInstruction(op = "dup"),
                        MethodInstruction(op = "invokespecial", owner = "Missing", name = "<init>", descriptor = "()V"),
                        SimpleInstruction(op = "pop"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(mainClass)
        // Only provide Main's bytecode, not Missing's
        val result = DynamicRunner.run("MainMissingDep", bytecode)
        assert(result is RunResult.Failure) { "Expected Failure but got $result" }
    }

    // ---------------------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------------------
    println()
    println("=== DynamicRunnerTest: $passed/$testNum passed ===")
    if (passed < testNum) {
        System.exit(1)
    }
}
