package dev.byteforge

import dev.byteforge.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString

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
    println("=== BytecodeModel Tests ===")
    println()

    val json = Json { ignoreUnknownKeys = true }

    // 1. SimpleInstruction deserialization
    test("SimpleInstruction deserialization (op = \"return\")") {
        val instr = json.decodeFromString<Instruction>("""{"op":"return"}""")
        assert(instr is SimpleInstruction) { "Expected SimpleInstruction, got ${instr::class.simpleName}" }
        assert(instr.op == "return") { "Expected op 'return', got '${instr.op}'" }
    }

    // 2. LdcInstruction with string value
    test("LdcInstruction with string value") {
        val instr = json.decodeFromString<Instruction>("""{"op":"ldc","value":"hello"}""")
        assert(instr is LdcInstruction) { "Expected LdcInstruction, got ${instr::class.simpleName}" }
        val ldc = instr as LdcInstruction
        assert(ldc.value == JsonPrimitive("hello")) { "Expected value 'hello', got '${ldc.value}'" }
    }

    // 3. LdcInstruction with int value
    test("LdcInstruction with int value") {
        val instr = json.decodeFromString<Instruction>("""{"op":"ldc","value":42}""")
        assert(instr is LdcInstruction) { "Expected LdcInstruction, got ${instr::class.simpleName}" }
        val ldc = instr as LdcInstruction
        assert(ldc.value == JsonPrimitive(42)) { "Expected value 42, got '${ldc.value}'" }
    }

    // 4. LdcInstruction with float value (has decimal point)
    test("LdcInstruction with float value") {
        val instr = json.decodeFromString<Instruction>("""{"op":"ldc","value":3.14}""")
        assert(instr is LdcInstruction) { "Expected LdcInstruction, got ${instr::class.simpleName}" }
        val ldc = instr as LdcInstruction
        assert(ldc.value.content == "3.14") { "Expected value 3.14, got '${ldc.value}'" }
    }

    // 5. LdcInstruction with long value (exceeds Int range)
    test("LdcInstruction with long value") {
        val instr = json.decodeFromString<Instruction>("""{"op":"ldc","value":3000000000}""")
        assert(instr is LdcInstruction) { "Expected LdcInstruction, got ${instr::class.simpleName}" }
        val ldc = instr as LdcInstruction
        assert(ldc.value.content == "3000000000") { "Expected value 3000000000, got '${ldc.value}'" }
    }

    // 6. FieldInstruction deserialization
    test("FieldInstruction deserialization (op = \"getstatic\")") {
        val instr = json.decodeFromString<Instruction>(
            """{"op":"getstatic","owner":"java/lang/System","name":"out","descriptor":"Ljava/io/PrintStream;"}"""
        )
        assert(instr is FieldInstruction) { "Expected FieldInstruction, got ${instr::class.simpleName}" }
        val fi = instr as FieldInstruction
        assert(fi.op == "getstatic") { "Expected op 'getstatic', got '${fi.op}'" }
        assert(fi.owner == "java/lang/System") { "Expected owner 'java/lang/System', got '${fi.owner}'" }
        assert(fi.name == "out") { "Expected name 'out', got '${fi.name}'" }
        assert(fi.descriptor == "Ljava/io/PrintStream;") { "Expected descriptor 'Ljava/io/PrintStream;', got '${fi.descriptor}'" }
    }

    // 7. MethodInstruction without isInterface
    test("MethodInstruction without isInterface") {
        val instr = json.decodeFromString<Instruction>(
            """{"op":"invokevirtual","owner":"java/io/PrintStream","name":"println","descriptor":"(Ljava/lang/String;)V"}"""
        )
        assert(instr is MethodInstruction) { "Expected MethodInstruction, got ${instr::class.simpleName}" }
        val mi = instr as MethodInstruction
        assert(mi.op == "invokevirtual") { "Expected op 'invokevirtual', got '${mi.op}'" }
        assert(!mi.isInterface) { "Expected isInterface=false" }
    }

    // 8. MethodInstruction with isInterface=true
    test("MethodInstruction with isInterface=true") {
        val instr = json.decodeFromString<Instruction>(
            """{"op":"invokeinterface","owner":"java/util/List","name":"size","descriptor":"()I","isInterface":true}"""
        )
        assert(instr is MethodInstruction) { "Expected MethodInstruction, got ${instr::class.simpleName}" }
        val mi = instr as MethodInstruction
        assert(mi.op == "invokeinterface") { "Expected op 'invokeinterface', got '${mi.op}'" }
        assert(mi.isInterface) { "Expected isInterface=true" }
    }

    // 9. VarInstruction deserialization
    test("VarInstruction deserialization (op = \"aload\")") {
        val instr = json.decodeFromString<Instruction>("""{"op":"aload","var":0}""")
        assert(instr is VarInstruction) { "Expected VarInstruction, got ${instr::class.simpleName}" }
        val vi = instr as VarInstruction
        assert(vi.op == "aload") { "Expected op 'aload', got '${vi.op}'" }
        assert(vi.varIndex == 0) { "Expected varIndex 0, got ${vi.varIndex}" }
    }

    // 10. TypeInstruction deserialization
    test("TypeInstruction deserialization (op = \"new\")") {
        val instr = json.decodeFromString<Instruction>("""{"op":"new","type":"java/lang/StringBuilder"}""")
        assert(instr is TypeInstruction) { "Expected TypeInstruction, got ${instr::class.simpleName}" }
        val ti = instr as TypeInstruction
        assert(ti.op == "new") { "Expected op 'new', got '${ti.op}'" }
        assert(ti.type == "java/lang/StringBuilder") { "Expected type 'java/lang/StringBuilder', got '${ti.type}'" }
    }

    // 11. IntInstruction deserialization
    test("IntInstruction deserialization (op = \"bipush\")") {
        val instr = json.decodeFromString<Instruction>("""{"op":"bipush","operand":100}""")
        assert(instr is IntInstruction) { "Expected IntInstruction, got ${instr::class.simpleName}" }
        val ii = instr as IntInstruction
        assert(ii.op == "bipush") { "Expected op 'bipush', got '${ii.op}'" }
        assert(ii.operand == 100) { "Expected operand 100, got ${ii.operand}" }
    }

    // 12. JumpInstruction deserialization
    test("JumpInstruction deserialization (op = \"goto\")") {
        val instr = json.decodeFromString<Instruction>("""{"op":"goto","label":"loop_start"}""")
        assert(instr is JumpInstruction) { "Expected JumpInstruction, got ${instr::class.simpleName}" }
        val ji = instr as JumpInstruction
        assert(ji.op == "goto") { "Expected op 'goto', got '${ji.op}'" }
        assert(ji.label == "loop_start") { "Expected label 'loop_start', got '${ji.label}'" }
    }

    // 13. LabelInstruction deserialization
    test("LabelInstruction deserialization (op = \"label\")") {
        val instr = json.decodeFromString<Instruction>("""{"op":"label","name":"loop_start"}""")
        assert(instr is LabelInstruction) { "Expected LabelInstruction, got ${instr::class.simpleName}" }
        val li = instr as LabelInstruction
        assert(li.op == "label") { "Expected op 'label', got '${li.op}'" }
        assert(li.name == "loop_start") { "Expected name 'loop_start', got '${li.name}'" }
    }

    // 14. Compact var: "aload_0" -> VarInstruction("aload", 0)
    test("Compact var: aload_0 -> VarInstruction(\"aload\", 0)") {
        val instr = json.decodeFromString<Instruction>("""{"op":"aload_0"}""")
        assert(instr is VarInstruction) { "Expected VarInstruction, got ${instr::class.simpleName}" }
        val vi = instr as VarInstruction
        assert(vi.op == "aload") { "Expected op 'aload', got '${vi.op}'" }
        assert(vi.varIndex == 0) { "Expected varIndex 0, got ${vi.varIndex}" }
    }

    // 15. Compact var: "istore_3" -> VarInstruction("istore", 3)
    test("Compact var: istore_3 -> VarInstruction(\"istore\", 3)") {
        val instr = json.decodeFromString<Instruction>("""{"op":"istore_3"}""")
        assert(instr is VarInstruction) { "Expected VarInstruction, got ${instr::class.simpleName}" }
        val vi = instr as VarInstruction
        assert(vi.op == "istore") { "Expected op 'istore', got '${vi.op}'" }
        assert(vi.varIndex == 3) { "Expected varIndex 3, got ${vi.varIndex}" }
    }

    // 16. Compact var: "dload_2" -> VarInstruction("dload", 2)
    test("Compact var: dload_2 -> VarInstruction(\"dload\", 2)") {
        val instr = json.decodeFromString<Instruction>("""{"op":"dload_2"}""")
        assert(instr is VarInstruction) { "Expected VarInstruction, got ${instr::class.simpleName}" }
        val vi = instr as VarInstruction
        assert(vi.op == "dload") { "Expected op 'dload', got '${vi.op}'" }
        assert(vi.varIndex == 2) { "Expected varIndex 2, got ${vi.varIndex}" }
    }

    // 17. Unknown op -> fallback SimpleInstruction
    test("Unknown op -> fallback SimpleInstruction") {
        val instr = json.decodeFromString<Instruction>("""{"op":"nop_unknown_xyz"}""")
        assert(instr is SimpleInstruction) { "Expected SimpleInstruction, got ${instr::class.simpleName}" }
        assert(instr.op == "nop_unknown_xyz") { "Expected op 'nop_unknown_xyz', got '${instr.op}'" }
    }

    // 18. ProgramDefinition serialization round-trip
    test("ProgramDefinition serialization round-trip") {
        val program = ProgramDefinition(
            classes = listOf(
                ClassDefinition(
                    name = "TestClass",
                    methods = listOf(
                        MethodDefinition(
                            access = listOf(AccessFlag.PUBLIC, AccessFlag.STATIC),
                            name = "main",
                            descriptor = "([Ljava/lang/String;)V",
                            instructions = listOf(SimpleInstruction("return"))
                        )
                    )
                )
            ),
            mainClass = "TestClass"
        )
        val serialized = json.encodeToString(program)
        val deserialized = json.decodeFromString<ProgramDefinition>(serialized)
        assert(deserialized == program) { "Round-trip failed: expected $program, got $deserialized" }
    }

    // 19. ClassDefinition defaults
    test("ClassDefinition defaults (superName, interfaces, access, fields)") {
        val classDef = ClassDefinition(
            name = "DefaultTest",
            methods = listOf(
                MethodDefinition(
                    access = listOf(AccessFlag.PUBLIC),
                    name = "test",
                    descriptor = "()V",
                    instructions = listOf(SimpleInstruction("return"))
                )
            )
        )
        assert(classDef.superName == "java/lang/Object") { "Expected superName 'java/lang/Object', got '${classDef.superName}'" }
        assert(classDef.interfaces.isEmpty()) { "Expected empty interfaces, got ${classDef.interfaces}" }
        assert(classDef.access == listOf(AccessFlag.PUBLIC, AccessFlag.SUPER)) { "Expected [PUBLIC, SUPER], got ${classDef.access}" }
        assert(classDef.fields.isEmpty()) { "Expected empty fields, got ${classDef.fields}" }
    }

    println()
    println("=== Results: $passed/$testNum passed ===")
    if (passed < testNum) {
        System.exit(1)
    }
}
