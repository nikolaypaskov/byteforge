package dev.byteforge.runtime

import java.io.File
import java.nio.file.Files

object WasmRunner {

    data class WasmResult(
        val output: String,
        val exitCode: Int,
        val runtime: String, // "wasmtime" or "node"
    )

    /**
     * Execute a .wasm binary and capture stdout.
     * Tries wasmtime first, falls back to Node.js.
     */
    fun run(wasmBytes: ByteArray, functionName: String = "_start"): WasmResult {
        return try {
            runWithWasmtime(wasmBytes)
        } catch (e: Exception) {
            try {
                runWithNode(wasmBytes, functionName)
            } catch (e2: Exception) {
                throw RuntimeException(
                    "No WASM runtime available. Install wasmtime or Node.js.\n" +
                    "wasmtime error: ${e.message}\n" +
                    "Node.js error: ${e2.message}"
                )
            }
        }
    }

    private fun runWithWasmtime(wasmBytes: ByteArray): WasmResult {
        val tempDir = Files.createTempDirectory("byteforge-wasm-run").toFile()
        try {
            val wasmFile = File(tempDir, "module.wasm")
            wasmFile.writeBytes(wasmBytes)

            val process = ProcessBuilder("wasmtime", wasmFile.absolutePath)
                .directory(tempDir)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            return WasmResult(
                output = stdout,
                exitCode = exitCode,
                runtime = "wasmtime",
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun runWithNode(wasmBytes: ByteArray, functionName: String): WasmResult {
        val tempDir = Files.createTempDirectory("byteforge-wasm-node").toFile()
        try {
            val wasmFile = File(tempDir, "module.wasm")
            wasmFile.writeBytes(wasmBytes)

            // Node.js script to load and run the WASM module
            val jsRunner = """
                const fs = require('fs');
                const wasmBytes = fs.readFileSync('${wasmFile.absolutePath}');

                // Set up WASI-like imports for fd_write
                const memory = new WebAssembly.Memory({ initial: 1 });
                const importObject = {
                    wasi_unstable: {
                        fd_write: (fd, iovs_ptr, iovs_len, nwritten_ptr) => {
                            const view = new DataView(memory.buffer);
                            let written = 0;
                            for (let i = 0; i < iovs_len; i++) {
                                const ptr = view.getUint32(iovs_ptr + i * 8, true);
                                const len = view.getUint32(iovs_ptr + i * 8 + 4, true);
                                const bytes = new Uint8Array(memory.buffer, ptr, len);
                                process.stdout.write(Buffer.from(bytes));
                                written += len;
                            }
                            view.setUint32(nwritten_ptr, written, true);
                            return 0;
                        },
                        proc_exit: (code) => { process.exit(code); },
                    },
                    env: { memory },
                };

                (async () => {
                    const module = await WebAssembly.instantiate(wasmBytes, importObject);
                    const exports = module.instance.exports;
                    if (exports.memory) {
                        // Use exported memory if available
                    }
                    if (exports._start) {
                        exports._start();
                    } else if (exports['$functionName']) {
                        const result = exports['$functionName']();
                        if (result !== undefined) console.log(result);
                    }
                })().catch(e => { console.error(e); process.exit(1); });
            """.trimIndent()

            val jsFile = File(tempDir, "runner.js")
            jsFile.writeText(jsRunner)

            val process = ProcessBuilder("node", jsFile.absolutePath)
                .directory(tempDir)
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            return WasmResult(
                output = stdout,
                exitCode = exitCode,
                runtime = "node",
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
