package dev.byteforge.compiler

import dev.byteforge.model.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class CompilationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object AsmCompiler {

    /**
     * Compiles a ClassDefinition into JVM bytecode (a valid .class file as byte[]).
     */
    fun compile(classDef: ClassDefinition): ByteArray {
        try {
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)

            val classAccess = classDef.access.fold(0) { acc, flag -> acc or flag.toAsm() }
            cw.visit(
                Opcodes.V17,
                classAccess,
                classDef.name,
                null,
                classDef.superName,
                classDef.interfaces.toTypedArray().ifEmpty { null },
            )

            // Define fields
            for (field in classDef.fields) {
                val fieldAccess = field.access.fold(0) { acc, flag -> acc or flag.toAsm() }
                cw.visitField(fieldAccess, field.name, field.descriptor, null, null).visitEnd()
            }

            // Auto-generate default <init> if none provided
            val hasInit = classDef.methods.any { it.name == "<init>" }
            if (!hasInit) {
                val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
                mv.visitCode()
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, classDef.superName, "<init>", "()V", false)
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(0, 0) // COMPUTE_MAXS handles this
                mv.visitEnd()
            }

            // Compile methods
            for (method in classDef.methods) {
                compileMethod(cw, method, classDef.superName)
            }

            cw.visitEnd()
            return cw.toByteArray()
        } catch (e: CompilationException) {
            throw e
        } catch (e: Exception) {
            throw CompilationException("Failed to compile class '${classDef.name}': ${e.message}", e)
        }
    }

    private fun compileMethod(cw: ClassWriter, method: MethodDefinition, superName: String) {
        val access = method.access.fold(0) { acc, flag -> acc or flag.toAsm() }
        val mv = cw.visitMethod(access, method.name, method.descriptor, null, null)
        mv.visitCode()

        // Pre-scan for labels so jump instructions can reference forward labels
        val labels = mutableMapOf<String, Label>()
        for (insn in method.instructions) {
            if (insn is LabelInstruction) {
                labels.getOrPut(insn.name) { Label() }
            }
            if (insn is JumpInstruction) {
                labels.getOrPut(insn.label) { Label() }
            }
        }

        for (insn in method.instructions) {
            emitInstruction(mv, insn, labels)
        }

        mv.visitMaxs(0, 0) // COMPUTE_MAXS handles this
        mv.visitEnd()
    }

    private fun emitInstruction(mv: MethodVisitor, insn: Instruction, labels: MutableMap<String, Label>) {
        when (insn) {
            is SimpleInstruction -> {
                mv.visitInsn(opcodeOf(insn.op))
            }

            is LdcInstruction -> {
                val value = insn.value
                val constant: Any = when {
                    value.isString -> value.content
                    value.content.contains(".") -> {
                        value.content.toDoubleOrNull()
                            ?: throw CompilationException("Invalid ldc double: ${value.content}")
                    }
                    else -> {
                        val longVal = value.content.toLongOrNull()
                            ?: throw CompilationException("Invalid ldc number: ${value.content}")
                        if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) longVal.toInt() else longVal
                    }
                }
                mv.visitLdcInsn(constant)
            }

            is FieldInstruction -> {
                mv.visitFieldInsn(opcodeOf(insn.op), insn.owner, insn.name, insn.descriptor)
            }

            is MethodInstruction -> {
                mv.visitMethodInsn(
                    opcodeOf(insn.op),
                    insn.owner,
                    insn.name,
                    insn.descriptor,
                    insn.isInterface,
                )
            }

            is VarInstruction -> {
                mv.visitVarInsn(opcodeOf(insn.op), insn.varIndex)
            }

            is TypeInstruction -> {
                mv.visitTypeInsn(opcodeOf(insn.op), insn.type)
            }

            is IntInstruction -> {
                mv.visitIntInsn(opcodeOf(insn.op), insn.operand)
            }

            is JumpInstruction -> {
                val label = labels.getOrPut(insn.label) { Label() }
                mv.visitJumpInsn(opcodeOf(insn.op), label)
            }

            is LabelInstruction -> {
                val label = labels.getOrPut(insn.name) { Label() }
                mv.visitLabel(label)
            }
        }
    }

    /**
     * Maps opcode name strings to ASM Opcodes.* int constants.
     */
    private fun opcodeOf(name: String): Int = when (name) {
        // Return instructions
        "return" -> Opcodes.RETURN
        "areturn" -> Opcodes.ARETURN
        "ireturn" -> Opcodes.IRETURN
        "lreturn" -> Opcodes.LRETURN
        "freturn" -> Opcodes.FRETURN
        "dreturn" -> Opcodes.DRETURN

        // Integer arithmetic
        "iadd" -> Opcodes.IADD
        "isub" -> Opcodes.ISUB
        "imul" -> Opcodes.IMUL
        "idiv" -> Opcodes.IDIV
        "irem" -> Opcodes.IREM
        "ineg" -> Opcodes.INEG

        // Long arithmetic
        "ladd" -> Opcodes.LADD
        "lsub" -> Opcodes.LSUB
        "lmul" -> Opcodes.LMUL
        "ldiv" -> Opcodes.LDIV

        // Float arithmetic
        "fadd" -> Opcodes.FADD
        "fsub" -> Opcodes.FSUB
        "fmul" -> Opcodes.FMUL
        "fdiv" -> Opcodes.FDIV

        // Double arithmetic
        "dadd" -> Opcodes.DADD
        "dsub" -> Opcodes.DSUB
        "dmul" -> Opcodes.DMUL
        "ddiv" -> Opcodes.DDIV

        // Type conversions
        "i2l" -> Opcodes.I2L
        "i2f" -> Opcodes.I2F
        "i2d" -> Opcodes.I2D
        "l2i" -> Opcodes.L2I
        "l2f" -> Opcodes.L2F
        "l2d" -> Opcodes.L2D
        "f2i" -> Opcodes.F2I
        "f2l" -> Opcodes.F2L
        "f2d" -> Opcodes.F2D
        "d2i" -> Opcodes.D2I
        "d2l" -> Opcodes.D2L
        "d2f" -> Opcodes.D2F
        "i2b" -> Opcodes.I2B
        "i2c" -> Opcodes.I2C
        "i2s" -> Opcodes.I2S

        // Comparisons
        "lcmp" -> Opcodes.LCMP
        "fcmpl" -> Opcodes.FCMPL
        "fcmpg" -> Opcodes.FCMPG
        "dcmpl" -> Opcodes.DCMPL
        "dcmpg" -> Opcodes.DCMPG

        // Constants
        "aconst_null" -> Opcodes.ACONST_NULL
        "iconst_m1" -> Opcodes.ICONST_M1
        "iconst_0" -> Opcodes.ICONST_0
        "iconst_1" -> Opcodes.ICONST_1
        "iconst_2" -> Opcodes.ICONST_2
        "iconst_3" -> Opcodes.ICONST_3
        "iconst_4" -> Opcodes.ICONST_4
        "iconst_5" -> Opcodes.ICONST_5
        "lconst_0" -> Opcodes.LCONST_0
        "lconst_1" -> Opcodes.LCONST_1
        "fconst_0" -> Opcodes.FCONST_0
        "fconst_1" -> Opcodes.FCONST_1
        "fconst_2" -> Opcodes.FCONST_2
        "dconst_0" -> Opcodes.DCONST_0
        "dconst_1" -> Opcodes.DCONST_1

        // Stack manipulation
        "dup" -> Opcodes.DUP
        "dup_x1" -> Opcodes.DUP_X1
        "dup_x2" -> Opcodes.DUP_X2
        "dup2" -> Opcodes.DUP2
        "dup2_x1" -> Opcodes.DUP2_X1
        "dup2_x2" -> Opcodes.DUP2_X2
        "pop" -> Opcodes.POP
        "pop2" -> Opcodes.POP2
        "swap" -> Opcodes.SWAP

        // Array operations
        "aaload" -> Opcodes.AALOAD
        "aastore" -> Opcodes.AASTORE
        "iaload" -> Opcodes.IALOAD
        "iastore" -> Opcodes.IASTORE
        "baload" -> Opcodes.BALOAD
        "bastore" -> Opcodes.BASTORE
        "caload" -> Opcodes.CALOAD
        "castore" -> Opcodes.CASTORE
        "saload" -> Opcodes.SALOAD
        "sastore" -> Opcodes.SASTORE
        "laload" -> Opcodes.LALOAD
        "lastore" -> Opcodes.LASTORE
        "faload" -> Opcodes.FALOAD
        "fastore" -> Opcodes.FASTORE
        "daload" -> Opcodes.DALOAD
        "dastore" -> Opcodes.DASTORE
        "arraylength" -> Opcodes.ARRAYLENGTH

        // Exception / monitor
        "athrow" -> Opcodes.ATHROW
        "monitorenter" -> Opcodes.MONITORENTER
        "monitorexit" -> Opcodes.MONITOREXIT

        // Field instructions
        "getstatic" -> Opcodes.GETSTATIC
        "putstatic" -> Opcodes.PUTSTATIC
        "getfield" -> Opcodes.GETFIELD
        "putfield" -> Opcodes.PUTFIELD

        // Method instructions
        "invokevirtual" -> Opcodes.INVOKEVIRTUAL
        "invokestatic" -> Opcodes.INVOKESTATIC
        "invokespecial" -> Opcodes.INVOKESPECIAL
        "invokeinterface" -> Opcodes.INVOKEINTERFACE

        // Variable load/store
        "iload" -> Opcodes.ILOAD
        "istore" -> Opcodes.ISTORE
        "aload" -> Opcodes.ALOAD
        "astore" -> Opcodes.ASTORE
        "fload" -> Opcodes.FLOAD
        "fstore" -> Opcodes.FSTORE
        "dload" -> Opcodes.DLOAD
        "dstore" -> Opcodes.DSTORE
        "lload" -> Opcodes.LLOAD
        "lstore" -> Opcodes.LSTORE

        // Type instructions
        "new" -> Opcodes.NEW
        "anewarray" -> Opcodes.ANEWARRAY
        "checkcast" -> Opcodes.CHECKCAST
        "instanceof" -> Opcodes.INSTANCEOF

        // Int instructions
        "bipush" -> Opcodes.BIPUSH
        "sipush" -> Opcodes.SIPUSH
        "newarray" -> Opcodes.NEWARRAY

        // Jump instructions
        "ifeq" -> Opcodes.IFEQ
        "ifne" -> Opcodes.IFNE
        "iflt" -> Opcodes.IFLT
        "ifge" -> Opcodes.IFGE
        "ifgt" -> Opcodes.IFGT
        "ifle" -> Opcodes.IFLE
        "if_icmpeq" -> Opcodes.IF_ICMPEQ
        "if_icmpne" -> Opcodes.IF_ICMPNE
        "if_icmplt" -> Opcodes.IF_ICMPLT
        "if_icmpge" -> Opcodes.IF_ICMPGE
        "if_icmpgt" -> Opcodes.IF_ICMPGT
        "if_icmple" -> Opcodes.IF_ICMPLE
        "if_acmpeq" -> Opcodes.IF_ACMPEQ
        "if_acmpne" -> Opcodes.IF_ACMPNE
        "ifnull" -> Opcodes.IFNULL
        "ifnonnull" -> Opcodes.IFNONNULL
        "goto" -> Opcodes.GOTO

        else -> throw CompilationException("Unknown opcode: '$name'")
    }

    private fun AccessFlag.toAsm(): Int = when (this) {
        AccessFlag.PUBLIC -> Opcodes.ACC_PUBLIC
        AccessFlag.PRIVATE -> Opcodes.ACC_PRIVATE
        AccessFlag.PROTECTED -> Opcodes.ACC_PROTECTED
        AccessFlag.STATIC -> Opcodes.ACC_STATIC
        AccessFlag.FINAL -> Opcodes.ACC_FINAL
        AccessFlag.SUPER -> Opcodes.ACC_SUPER
        AccessFlag.ABSTRACT -> Opcodes.ACC_ABSTRACT
        AccessFlag.INTERFACE -> Opcodes.ACC_INTERFACE
    }
}
