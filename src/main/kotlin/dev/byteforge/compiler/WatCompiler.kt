package dev.byteforge.compiler

import dev.byteforge.model.*
import java.io.File
import java.nio.file.Files

object WatCompiler {

    /**
     * Compiles a WasmModule into WAT text format, then invokes wat2wasm to produce a .wasm binary.
     */
    fun compile(module: WasmModule): ByteArray {
        val wat = toWat(module)
        return assembleWat(wat)
    }

    /**
     * Convert WasmModule to WAT (WebAssembly Text format) string.
     */
    fun toWat(module: WasmModule): String {
        val sb = StringBuilder()
        sb.appendLine("(module")

        // Imports
        for (imp in module.imports) {
            when (imp.kind) {
                "func" -> {
                    val params = imp.params.joinToString(" ") { "(param ${it.toWat()})" }
                    val results = imp.results.joinToString(" ") { "(result ${it.toWat()})" }
                    val funcName = imp.funcName ?: imp.name
                    sb.appendLine("  (import \"${imp.module}\" \"${imp.name}\" (func \$${funcName} $params $results))")
                }
                "memory" -> {
                    val min = imp.memoryMin ?: 1
                    val maxStr = if (imp.memoryMax != null) " ${imp.memoryMax}" else ""
                    sb.appendLine("  (import \"${imp.module}\" \"${imp.name}\" (memory $min$maxStr))")
                }
            }
        }

        // Memory (if not imported and memoryPages > 0)
        val hasImportedMemory = module.imports.any { it.kind == "memory" }
        if (!hasImportedMemory && module.memoryPages > 0) {
            sb.appendLine("  (memory ${module.memoryPages})")
        }

        // Data segments
        for (seg in module.data) {
            val escaped = seg.data.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.appendLine("  (data (i32.const ${seg.offset}) \"$escaped\")")
        }

        // Functions
        for (func in module.functions) {
            val params = func.params.joinToString(" ") { "(param ${it.toWat()})" }
            val results = func.results.joinToString(" ") { "(result ${it.toWat()})" }
            val locals = func.locals.joinToString(" ") { "(local ${it.toWat()})" }
            sb.appendLine("  (func \$${func.name} $params $results")
            if (locals.isNotBlank()) sb.appendLine("    $locals")
            for (insn in func.instructions) {
                val valueStr = if (insn.value != null) " ${insn.value}" else ""
                sb.appendLine("    ${insn.op}${valueStr}")
            }
            sb.appendLine("  )")
        }

        // Exports
        for (exp in module.exports) {
            sb.appendLine("  (export \"${exp.name}\" (${exp.kind} \$${exp.ref}))")
        }

        sb.appendLine(")")
        return sb.toString()
    }

    /**
     * Invoke wat2wasm to assemble WAT text into a .wasm binary.
     */
    private fun assembleWat(wat: String): ByteArray {
        val tempDir = Files.createTempDirectory("byteforge-wasm").toFile()
        try {
            val watFile = File(tempDir, "module.wat")
            val wasmFile = File(tempDir, "module.wasm")
            watFile.writeText(wat)

            val process = ProcessBuilder("wat2wasm", watFile.absolutePath, "-o", wasmFile.absolutePath)
                .directory(tempDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw CompilationException("wat2wasm failed (exit $exitCode):\n$output")
            }
            return wasmFile.readBytes()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun WasmType.toWat(): String = when (this) {
        WasmType.I32 -> "i32"
        WasmType.I64 -> "i64"
        WasmType.F32 -> "f32"
        WasmType.F64 -> "f64"
    }
}
