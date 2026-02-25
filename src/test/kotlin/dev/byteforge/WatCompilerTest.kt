package dev.byteforge

import dev.byteforge.compiler.WatCompiler
import dev.byteforge.model.*

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
    println("=== WatCompiler Tests ===")
    println()

    // 1. Simple function with i32
    test("Simple function with i32 param and result") {
        val module = WasmModule(
            functions = listOf(
                WasmFunction(
                    name = "add",
                    params = listOf(WasmType.I32),
                    results = listOf(WasmType.I32),
                    instructions = listOf(WasmInstruction("i32.const", "42"))
                )
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("(func \$add (param i32) (result i32)")) { "Expected func signature with i32 param and result, got:\n$wat" }
        assert(wat.contains("i32.const 42")) { "Expected i32.const 42 instruction, got:\n$wat" }
    }

    // 2. Module with func import
    test("Module with func import") {
        val module = WasmModule(
            imports = listOf(
                WasmImport(
                    module = "env",
                    name = "log",
                    kind = "func",
                    params = listOf(WasmType.I32),
                    results = listOf(WasmType.I32)
                )
            ),
            functions = listOf(
                WasmFunction(name = "dummy", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("(import \"env\" \"log\" (func \$log")) { "Expected func import for 'log', got:\n$wat" }
        assert(wat.contains("(param i32)")) { "Expected param i32 in import, got:\n$wat" }
        assert(wat.contains("(result i32)")) { "Expected result i32 in import, got:\n$wat" }
    }

    // 3. Module with memory import
    test("Module with memory import") {
        val module = WasmModule(
            imports = listOf(
                WasmImport(
                    module = "env",
                    name = "memory",
                    kind = "memory",
                    memoryMin = 1
                )
            ),
            functions = listOf(
                WasmFunction(name = "dummy", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("(import \"env\" \"memory\" (memory 1))")) { "Expected memory import, got:\n$wat" }
    }

    // 4. Exports
    test("Exports") {
        val module = WasmModule(
            exports = listOf(
                WasmExport(name = "addTwo", kind = "func", ref = "add")
            ),
            functions = listOf(
                WasmFunction(name = "add", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("(export \"addTwo\" (func \$add))")) { "Expected func export, got:\n$wat" }
    }

    // 5. Data segments
    test("Data segments") {
        val module = WasmModule(
            data = listOf(
                WasmDataSegment(offset = 0, data = "hello")
            ),
            functions = listOf(
                WasmFunction(name = "dummy", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("(data (i32.const 0) \"hello\")")) { "Expected data segment, got:\n$wat" }
    }

    // 6. Locals
    test("Locals") {
        val module = WasmModule(
            functions = listOf(
                WasmFunction(
                    name = "withLocals",
                    locals = listOf(WasmType.I32, WasmType.I64),
                    instructions = listOf(WasmInstruction("nop"))
                )
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("(local i32)")) { "Expected (local i32), got:\n$wat" }
        assert(wat.contains("(local i64)")) { "Expected (local i64), got:\n$wat" }
    }

    // 7. Module wrapping
    test("Module wrapping — starts with (module and ends with )") {
        val module = WasmModule(
            functions = listOf(
                WasmFunction(name = "dummy", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.trimStart().startsWith("(module")) { "Expected WAT to start with '(module', got:\n$wat" }
        assert(wat.trimEnd().endsWith(")")) { "Expected WAT to end with ')', got:\n$wat" }
    }

    // 8. Multiple instructions in order
    test("Multiple instructions in order") {
        val module = WasmModule(
            functions = listOf(
                WasmFunction(
                    name = "ordered",
                    params = listOf(WasmType.I32),
                    results = listOf(WasmType.I32),
                    instructions = listOf(
                        WasmInstruction("i32.const", "1"),
                        WasmInstruction("i32.const", "2"),
                        WasmInstruction("i32.add")
                    )
                )
            )
        )
        val wat = WatCompiler.toWat(module)
        val idx1 = wat.indexOf("i32.const 1")
        val idx2 = wat.indexOf("i32.const 2")
        val idx3 = wat.indexOf("i32.add")
        assert(idx1 >= 0) { "Expected i32.const 1 in WAT" }
        assert(idx2 >= 0) { "Expected i32.const 2 in WAT" }
        assert(idx3 >= 0) { "Expected i32.add in WAT" }
        assert(idx1 < idx2) { "Expected i32.const 1 before i32.const 2" }
        assert(idx2 < idx3) { "Expected i32.const 2 before i32.add" }
    }

    // 9. Memory declaration when non-imported
    test("Memory declaration when non-imported") {
        val module = WasmModule(
            memoryPages = 2,
            functions = listOf(
                WasmFunction(name = "dummy", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("(memory 2)")) { "Expected (memory 2) declaration, got:\n$wat" }
    }

    // 10. No duplicate memory when imported
    test("No duplicate memory when imported") {
        val module = WasmModule(
            imports = listOf(
                WasmImport(
                    module = "env",
                    name = "memory",
                    kind = "memory",
                    memoryMin = 1
                )
            ),
            memoryPages = 1,
            functions = listOf(
                WasmFunction(name = "dummy", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("(import \"env\" \"memory\" (memory 1))")) { "Expected memory import line" }
        // Count occurrences of "(memory" — should be exactly 1 (inside the import)
        val memoryCount = Regex("\\(memory ").findAll(wat).count()
        assert(memoryCount == 1) { "Expected exactly 1 occurrence of '(memory' (in import), found $memoryCount" }
    }

    // 11. Data segment special character escaping
    test("Data segment special character escaping") {
        val module = WasmModule(
            data = listOf(
                WasmDataSegment(offset = 0, data = "back\\slash and \"quotes\"")
            ),
            functions = listOf(
                WasmFunction(name = "dummy", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("back\\\\slash")) { "Expected backslash to be escaped to \\\\, got:\n$wat" }
        assert(wat.contains("\\\"quotes\\\"")) { "Expected quotes to be escaped to \\\", got:\n$wat" }
    }

    // 12. Import funcName override
    test("Import funcName override") {
        val module = WasmModule(
            imports = listOf(
                WasmImport(
                    module = "env",
                    name = "original",
                    kind = "func",
                    funcName = "myFunc",
                    params = listOf(WasmType.I32)
                )
            ),
            functions = listOf(
                WasmFunction(name = "dummy", instructions = listOf(WasmInstruction("nop")))
            )
        )
        val wat = WatCompiler.toWat(module)
        assert(wat.contains("\$myFunc")) { "Expected \$myFunc in WAT, got:\n$wat" }
        assert(!wat.contains("\$original")) { "Expected \$original NOT in WAT (funcName override should replace it), got:\n$wat" }
    }

    println()
    println("=== Results: $passed/$testNum passed ===")
    if (passed < testNum) {
        System.exit(1)
    }
}
