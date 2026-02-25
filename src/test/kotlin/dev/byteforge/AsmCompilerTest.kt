package dev.byteforge

import dev.byteforge.compiler.AsmCompiler
import dev.byteforge.compiler.CompilationException
import dev.byteforge.model.*
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
    println("=== AsmCompilerTest ===")
    println()

    // ---------------------------------------------------------------------------
    // 1. Minimal class — compile a class with just a main method that returns
    // ---------------------------------------------------------------------------
    test("Minimal class compiles to non-empty bytecode") {
        val classDef = ClassDefinition(
            name = "Minimal",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(SimpleInstruction(op = "return")),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        assert(bytecode.isNotEmpty()) { "Bytecode should be non-empty" }
    }

    // ---------------------------------------------------------------------------
    // 2. Class with fields — compile a class with a private String field
    // ---------------------------------------------------------------------------
    test("Class with fields produces larger bytecode than minimal") {
        val minimalDef = ClassDefinition(
            name = "MinimalForSize",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(SimpleInstruction(op = "return")),
                )
            ),
        )
        val withFieldDef = ClassDefinition(
            name = "WithField",
            fields = listOf(
                FieldDefinition(
                    access = listOf(AccessFlag.PRIVATE),
                    name = "name",
                    descriptor = "Ljava/lang/String;",
                )
            ),
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(SimpleInstruction(op = "return")),
                )
            ),
        )
        val minBytes = AsmCompiler.compile(minimalDef)
        val fieldBytes = AsmCompiler.compile(withFieldDef)
        assert(fieldBytes.isNotEmpty()) { "Bytecode with field should be non-empty" }
        assert(fieldBytes.size > minBytes.size) {
            "Bytecode with field (${fieldBytes.size}) should be larger than minimal (${minBytes.size})"
        }
    }

    // ---------------------------------------------------------------------------
    // 3. Class with multiple methods — compile a class with 2+ methods
    // ---------------------------------------------------------------------------
    test("Class with multiple methods compiles successfully") {
        val classDef = ClassDefinition(
            name = "MultiMethod",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(SimpleInstruction(op = "return")),
                ),
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "helper",
                    descriptor = "()V",
                    instructions = listOf(SimpleInstruction(op = "return")),
                ),
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        assert(bytecode.isNotEmpty()) { "Bytecode should be non-empty" }
    }

    // ---------------------------------------------------------------------------
    // 4. Jump instructions with forward labels — loop printing 0-4
    // ---------------------------------------------------------------------------
    test("Jump instructions with forward labels — loop prints 0 to 4") {
        val classDef = ClassDefinition(
            name = "LoopTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        // int i = 0
                        SimpleInstruction(op = "iconst_0"),
                        VarInstruction(op = "istore", varIndex = 1),
                        // loop:
                        LabelInstruction(op = "label", name = "loop"),
                        // if (i >= 5) goto end
                        VarInstruction(op = "iload", varIndex = 1),
                        IntInstruction(op = "bipush", operand = 5),
                        JumpInstruction(op = "if_icmpge", label = "end"),
                        // System.out.println(i)
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        VarInstruction(op = "iload", varIndex = 1),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(I)V"),
                        // i++
                        VarInstruction(op = "iload", varIndex = 1),
                        SimpleInstruction(op = "iconst_1"),
                        SimpleInstruction(op = "iadd"),
                        VarInstruction(op = "istore", varIndex = 1),
                        // goto loop
                        JumpInstruction(op = "goto", label = "loop"),
                        // end:
                        LabelInstruction(op = "label", name = "end"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("LoopTest", bytecode)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output
        assert(output == "0\n1\n2\n3\n4\n") {
            "Expected '0\\n1\\n2\\n3\\n4\\n' but got '${output.replace("\n", "\\n")}'"
        }
    }

    // ---------------------------------------------------------------------------
    // 5. Integer arithmetic — iadd: 10 + 3 = 13
    // ---------------------------------------------------------------------------
    test("Integer arithmetic — iadd produces correct result") {
        val classDef = ClassDefinition(
            name = "ArithTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        IntInstruction(op = "bipush", operand = 10),
                        IntInstruction(op = "bipush", operand = 3),
                        SimpleInstruction(op = "iadd"),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(I)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("ArithTest", bytecode)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output.trim()
        assert(output == "13") { "Expected '13' but got '$output'" }
    }

    // ---------------------------------------------------------------------------
    // 6. Type conversions — i2l, i2f, i2d compile without error
    // ---------------------------------------------------------------------------
    test("Type conversions (i2l, i2f, i2d) compile successfully") {
        val classDef = ClassDefinition(
            name = "ConversionTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        // push 42, convert to long, pop2 (long is 2 slots)
                        IntInstruction(op = "bipush", operand = 42),
                        SimpleInstruction(op = "i2l"),
                        SimpleInstruction(op = "pop2"),
                        // push 42, convert to float, pop
                        IntInstruction(op = "bipush", operand = 42),
                        SimpleInstruction(op = "i2f"),
                        SimpleInstruction(op = "pop"),
                        // push 42, convert to double, pop2
                        IntInstruction(op = "bipush", operand = 42),
                        SimpleInstruction(op = "i2d"),
                        SimpleInstruction(op = "pop2"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("ConversionTest", bytecode)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
    }

    // ---------------------------------------------------------------------------
    // 7. Array operations — newarray, iastore, iaload
    // ---------------------------------------------------------------------------
    test("Array operations — store and load int from array") {
        val classDef = ClassDefinition(
            name = "ArrayTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        // int[] arr = new int[5]
                        IntInstruction(op = "bipush", operand = 5),
                        IntInstruction(op = "newarray", operand = 10), // T_INT = 10
                        // arr[0] = 42
                        SimpleInstruction(op = "dup"),
                        SimpleInstruction(op = "iconst_0"),
                        IntInstruction(op = "bipush", operand = 42),
                        SimpleInstruction(op = "iastore"),
                        // System.out.println(arr[0])
                        SimpleInstruction(op = "iconst_0"),
                        SimpleInstruction(op = "iaload"),
                        VarInstruction(op = "istore", varIndex = 1),
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        VarInstruction(op = "iload", varIndex = 1),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(I)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("ArrayTest", bytecode)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output.trim()
        assert(output == "42") { "Expected '42' but got '$output'" }
    }

    // ---------------------------------------------------------------------------
    // 8. Unknown opcode throws CompilationException
    // ---------------------------------------------------------------------------
    test("Unknown opcode throws CompilationException") {
        val classDef = ClassDefinition(
            name = "BadOpcode",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        SimpleInstruction(op = "not_a_real_opcode"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        var caught = false
        try {
            AsmCompiler.compile(classDef)
        } catch (e: CompilationException) {
            caught = true
            assert(e.message!!.contains("not_a_real_opcode")) {
                "Exception message should mention the bad opcode"
            }
        }
        assert(caught) { "Expected CompilationException to be thrown" }
    }

    // ---------------------------------------------------------------------------
    // 9. compileAll with ProgramDefinition — 2 classes
    // ---------------------------------------------------------------------------
    test("compileAll produces map with correct number of entries") {
        val class1 = ClassDefinition(
            name = "ClassA",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(SimpleInstruction(op = "return")),
                )
            ),
        )
        val class2 = ClassDefinition(
            name = "ClassB",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "doSomething",
                    descriptor = "()V",
                    instructions = listOf(SimpleInstruction(op = "return")),
                )
            ),
        )
        val program = ProgramDefinition(classes = listOf(class1, class2), mainClass = "ClassA")
        val bytecodeMap = AsmCompiler.compileAll(program)
        assert(bytecodeMap.size == 2) { "Expected 2 entries, got ${bytecodeMap.size}" }
        assert(bytecodeMap.containsKey("ClassA")) { "Missing ClassA in bytecode map" }
        assert(bytecodeMap.containsKey("ClassB")) { "Missing ClassB in bytecode map" }
        assert(bytecodeMap["ClassA"]!!.isNotEmpty()) { "ClassA bytecode should be non-empty" }
        assert(bytecodeMap["ClassB"]!!.isNotEmpty()) { "ClassB bytecode should be non-empty" }
    }

    // ---------------------------------------------------------------------------
    // 10. Auto-generated default constructor
    // ---------------------------------------------------------------------------
    test("Auto-generated default constructor allows instantiation") {
        // MyObj class with no <init> — compiler should auto-generate one
        val myObjClass = ClassDefinition(
            name = "MyObj",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC),
                    name = "greet",
                    descriptor = "()V",
                    instructions = listOf(
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        LdcInstruction(op = "ldc", value = JsonPrimitive("constructed")),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(Ljava/lang/String;)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        // Main class that instantiates MyObj using the auto-generated <init>
        val mainClass = ClassDefinition(
            name = "AutoInitMain",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        TypeInstruction(op = "new", type = "MyObj"),
                        SimpleInstruction(op = "dup"),
                        MethodInstruction(op = "invokespecial", owner = "MyObj", name = "<init>", descriptor = "()V"),
                        MethodInstruction(op = "invokevirtual", owner = "MyObj", name = "greet", descriptor = "()V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val program = ProgramDefinition(classes = listOf(myObjClass, mainClass), mainClass = "AutoInitMain")
        val bytecodeMap = AsmCompiler.compileAll(program)
        val result = DynamicRunner.run("AutoInitMain", bytecodeMap)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output.trim()
        assert(output == "constructed") { "Expected 'constructed' but got '$output'" }
    }

    // ---------------------------------------------------------------------------
    // 11. LDC with string
    // ---------------------------------------------------------------------------
    test("LDC with string constant prints correctly") {
        val classDef = ClassDefinition(
            name = "LdcStringTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        LdcInstruction(op = "ldc", value = JsonPrimitive("test string")),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(Ljava/lang/String;)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("LdcStringTest", bytecode)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output.trim()
        assert(output == "test string") { "Expected 'test string' but got '$output'" }
    }

    // ---------------------------------------------------------------------------
    // 12. LDC with int, float, long
    // ---------------------------------------------------------------------------
    test("LDC with int, float, and long constants compiles correctly") {
        // Test int LDC
        val intClass = ClassDefinition(
            name = "LdcIntTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        LdcInstruction(op = "ldc", value = JsonPrimitive(42)),
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(I)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val intBytecode = AsmCompiler.compile(intClass)
        val intResult = DynamicRunner.run("LdcIntTest", intBytecode)
        assert(intResult is RunResult.Success) { "Int LDC: expected Success but got $intResult" }
        assert((intResult as RunResult.Success).output.trim() == "42") {
            "Int LDC: expected '42' but got '${intResult.output.trim()}'"
        }

        // Test float LDC (contains ".")
        val floatClass = ClassDefinition(
            name = "LdcFloatTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        LdcInstruction(op = "ldc", value = JsonPrimitive(3.14)),
                        SimpleInstruction(op = "pop2"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val floatBytecode = AsmCompiler.compile(floatClass)
        assert(floatBytecode.isNotEmpty()) { "Float LDC: bytecode should be non-empty" }

        // Test long LDC (exceeds Int range)
        val longClass = ClassDefinition(
            name = "LdcLongTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        LdcInstruction(op = "ldc", value = JsonPrimitive(3000000000L)),
                        SimpleInstruction(op = "pop2"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val longBytecode = AsmCompiler.compile(longClass)
        assert(longBytecode.isNotEmpty()) { "Long LDC: bytecode should be non-empty" }
    }

    // ---------------------------------------------------------------------------
    // 13. Stack operations — dup, pop
    // ---------------------------------------------------------------------------
    test("Stack operations — dup and pop leave correct value") {
        val classDef = ClassDefinition(
            name = "StackOpsTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                    name = "main",
                    descriptor = "([Ljava/lang/String;)V",
                    instructions = listOf(
                        // push 99, dup (now 99,99 on stack), pop (now 99), print
                        FieldInstruction(op = "getstatic", owner = "java/lang/System", name = "out", descriptor = "Ljava/io/PrintStream;"),
                        IntInstruction(op = "bipush", operand = 99),
                        SimpleInstruction(op = "dup"),
                        SimpleInstruction(op = "pop"),
                        // stack: PrintStream, 99
                        MethodInstruction(op = "invokevirtual", owner = "java/io/PrintStream", name = "println", descriptor = "(I)V"),
                        SimpleInstruction(op = "return"),
                    ),
                )
            ),
        )
        val bytecode = AsmCompiler.compile(classDef)
        val result = DynamicRunner.run("StackOpsTest", bytecode)
        assert(result is RunResult.Success) { "Expected Success but got $result" }
        val output = (result as RunResult.Success).output.trim()
        assert(output == "99") { "Expected '99' but got '$output'" }
    }

    // ---------------------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------------------
    println()
    println("=== AsmCompilerTest: $passed/$testNum passed ===")
    if (passed < testNum) {
        System.exit(1)
    }
}
