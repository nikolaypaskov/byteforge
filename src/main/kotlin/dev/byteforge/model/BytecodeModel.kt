package dev.byteforge.model

import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/**
 * Access flags for classes, methods, and fields — maps to ASM Opcodes.ACC_* constants.
 */
@Serializable
enum class AccessFlag {
    @SerialName("public") PUBLIC,
    @SerialName("private") PRIVATE,
    @SerialName("protected") PROTECTED,
    @SerialName("static") STATIC,
    @SerialName("final") FINAL,
    @SerialName("super") SUPER,
    @SerialName("abstract") ABSTRACT,
    @SerialName("interface") INTERFACE,
}

/**
 * Sealed hierarchy of JVM bytecode instructions.
 * Discriminated on the "op" field in JSON.
 */
@Serializable(with = InstructionDeserializer::class)
sealed class Instruction {
    abstract val op: String
}

/** Zero-operand instructions: areturn, return, iadd, isub, imul, idiv, aconst_null, etc. */
@Serializable
data class SimpleInstruction(override val op: String) : Instruction()

/** ldc — push a constant (String, Int, Float, Long, Double) */
@Serializable
data class LdcInstruction(override val op: String, val value: JsonPrimitive) : Instruction()

/** getstatic, putstatic, getfield, putfield */
@Serializable
data class FieldInstruction(
    override val op: String,
    val owner: String,
    val name: String,
    val descriptor: String,
) : Instruction()

/** invokevirtual, invokestatic, invokespecial, invokeinterface */
@Serializable
data class MethodInstruction(
    override val op: String,
    val owner: String,
    val name: String,
    val descriptor: String,
    val isInterface: Boolean = false,
) : Instruction()

/** iload, istore, aload, astore, fload, fstore, dload, dstore, lload, lstore */
@Serializable
data class VarInstruction(
    override val op: String,
    @SerialName("var") val varIndex: Int,
) : Instruction()

/** new, anewarray, checkcast, instanceof */
@Serializable
data class TypeInstruction(
    override val op: String,
    val type: String,
) : Instruction()

/** bipush, sipush, newarray */
@Serializable
data class IntInstruction(
    override val op: String,
    val operand: Int,
) : Instruction()

/** ifeq, ifne, iflt, ifge, ifgt, ifle, if_icmpeq, goto, etc. */
@Serializable
data class JumpInstruction(
    override val op: String,
    val label: String,
) : Instruction()

/** Label pseudo-instruction — marks a target for jumps */
@Serializable
data class LabelInstruction(
    override val op: String,
    val name: String,
) : Instruction()

/** A field definition within a class. */
@Serializable
data class FieldDefinition(
    val access: List<AccessFlag>,
    val name: String,
    val descriptor: String,
)

/** A method definition with its instruction sequence. */
@Serializable
data class MethodDefinition(
    val access: List<AccessFlag>,
    val name: String,
    val descriptor: String,
    val instructions: List<Instruction>,
)

/** Top-level class definition — the root of what Claude returns. */
@Serializable
data class ClassDefinition(
    val name: String,
    val superName: String = "java/lang/Object",
    val interfaces: List<String> = emptyList(),
    val access: List<AccessFlag> = listOf(AccessFlag.PUBLIC, AccessFlag.SUPER),
    val fields: List<FieldDefinition> = emptyList(),
    val methods: List<MethodDefinition>,
)

/**
 * Custom deserializer that dispatches on the "op" field to pick the right Instruction subtype.
 */
object InstructionDeserializer : KSerializer<Instruction> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Instruction")

    private val simpleOps = setOf(
        "return", "areturn", "ireturn", "lreturn", "freturn", "dreturn",
        "iadd", "isub", "imul", "idiv", "irem", "ineg",
        "ladd", "lsub", "lmul", "ldiv",
        "fadd", "fsub", "fmul", "fdiv",
        "dadd", "dsub", "dmul", "ddiv",
        "i2l", "i2f", "i2d", "l2i", "l2f", "l2d", "f2i", "f2l", "f2d", "d2i", "d2l", "d2f",
        "i2b", "i2c", "i2s",
        "lcmp", "fcmpl", "fcmpg", "dcmpl", "dcmpg",
        "aconst_null",
        "iconst_m1", "iconst_0", "iconst_1", "iconst_2", "iconst_3", "iconst_4", "iconst_5",
        "lconst_0", "lconst_1", "fconst_0", "fconst_1", "fconst_2", "dconst_0", "dconst_1",
        "dup", "dup_x1", "dup_x2", "dup2", "dup2_x1", "dup2_x2",
        "pop", "pop2", "swap",
        "aaload", "aastore", "iaload", "iastore", "baload", "bastore",
        "caload", "castore", "saload", "sastore", "laload", "lastore",
        "faload", "fastore", "daload", "dastore",
        "arraylength",
        "athrow",
        "monitorenter", "monitorexit",
    )

    private val fieldOps = setOf("getstatic", "putstatic", "getfield", "putfield")
    private val methodOps = setOf("invokevirtual", "invokestatic", "invokespecial", "invokeinterface")
    private val varOps = setOf(
        "iload", "istore", "aload", "astore",
        "fload", "fstore", "dload", "dstore",
        "lload", "lstore",
    )
    private val typeOps = setOf("new", "anewarray", "checkcast", "instanceof")
    private val intOps = setOf("bipush", "sipush", "newarray")
    private val jumpOps = setOf(
        "ifeq", "ifne", "iflt", "ifge", "ifgt", "ifle",
        "if_icmpeq", "if_icmpne", "if_icmplt", "if_icmpge", "if_icmpgt", "if_icmple",
        "if_acmpeq", "if_acmpne", "ifnull", "ifnonnull",
        "goto",
    )

    override fun serialize(encoder: Encoder, value: Instruction) {
        val json = Json { encodeDefaults = true }
        val element = when (value) {
            is SimpleInstruction -> json.encodeToJsonElement(SimpleInstruction.serializer(), value)
            is LdcInstruction -> json.encodeToJsonElement(LdcInstruction.serializer(), value)
            is FieldInstruction -> json.encodeToJsonElement(FieldInstruction.serializer(), value)
            is MethodInstruction -> json.encodeToJsonElement(MethodInstruction.serializer(), value)
            is VarInstruction -> json.encodeToJsonElement(VarInstruction.serializer(), value)
            is TypeInstruction -> json.encodeToJsonElement(TypeInstruction.serializer(), value)
            is IntInstruction -> json.encodeToJsonElement(IntInstruction.serializer(), value)
            is JumpInstruction -> json.encodeToJsonElement(JumpInstruction.serializer(), value)
            is LabelInstruction -> json.encodeToJsonElement(LabelInstruction.serializer(), value)
        }
        encoder as JsonEncoder
        encoder.encodeJsonElement(element)
    }

    /**
     * Regex to match compact var instructions like astore_1, iload_0, aload_3, etc.
     * These are shorthand for the equivalent VarInstruction with a fixed index.
     */
    private val compactVarRegex = Regex("^(aload|astore|iload|istore|fload|fstore|dload|dstore|lload|lstore)_(\\d)$")

    override fun deserialize(decoder: Decoder): Instruction {
        decoder as JsonDecoder
        val jsonObject = decoder.decodeJsonElement().jsonObject
        val op = jsonObject["op"]?.jsonPrimitive?.content
            ?: error("Instruction missing 'op' field: $jsonObject")

        // Handle compact var instructions: astore_1 → VarInstruction("astore", 1)
        compactVarRegex.matchEntire(op)?.let { match ->
            val baseOp = match.groupValues[1]
            val index = match.groupValues[2].toInt()
            return VarInstruction(baseOp, index)
        }

        return when {
            op == "ldc" -> Json.decodeFromJsonElement(LdcInstruction.serializer(), jsonObject)
            op == "label" -> Json.decodeFromJsonElement(LabelInstruction.serializer(), jsonObject)
            op in fieldOps -> Json.decodeFromJsonElement(FieldInstruction.serializer(), jsonObject)
            op in methodOps -> Json.decodeFromJsonElement(MethodInstruction.serializer(), jsonObject)
            op in varOps -> Json.decodeFromJsonElement(VarInstruction.serializer(), jsonObject)
            op in typeOps -> Json.decodeFromJsonElement(TypeInstruction.serializer(), jsonObject)
            op in intOps -> Json.decodeFromJsonElement(IntInstruction.serializer(), jsonObject)
            op in jumpOps -> Json.decodeFromJsonElement(JumpInstruction.serializer(), jsonObject)
            op in simpleOps -> SimpleInstruction(op)
            else -> {
                // Fallback: treat unknown ops as simple instructions
                // This is lenient — ASM will error if the op is truly invalid
                SimpleInstruction(op)
            }
        }
    }
}
