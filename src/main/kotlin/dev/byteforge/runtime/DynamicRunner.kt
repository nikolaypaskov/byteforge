package dev.byteforge.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

sealed class RunResult {
    data class Success(val output: String) : RunResult()
    data class Failure(val error: String, val exception: Throwable? = null) : RunResult()
}

/**
 * ClassLoader that loads classes from in-memory byte arrays.
 */
class BytecodeClassLoader(
    private val classes: Map<String, ByteArray>,
    parent: ClassLoader = getSystemClassLoader(),
) : ClassLoader(parent) {

    override fun findClass(name: String): Class<*> {
        val bytes = classes[name]
            ?: throw ClassNotFoundException("Class '$name' not found in bytecode map")
        return defineClass(name, bytes, 0, bytes.size)
    }
}

object DynamicRunner {

    /**
     * Loads a class from bytecode and invokes its main(String[]) method.
     * Captures stdout and returns the result.
     *
     * @param className Fully qualified class name (dot-separated, e.g. "Hello")
     * @param bytecode The compiled .class bytes
     * @param saveClass If true, writes the .class file to disk for javap debugging
     */
    fun run(className: String, bytecode: ByteArray, saveClass: Boolean = false): RunResult {
        if (saveClass) {
            saveClassFile(className, bytecode)
        }

        return try {
            val classLoader = BytecodeClassLoader(mapOf(className to bytecode))
            val clazz = classLoader.loadClass(className)

            val mainMethod = try {
                clazz.getMethod("main", Array<String>::class.java)
            } catch (e: NoSuchMethodException) {
                return RunResult.Failure("Class '$className' has no main(String[]) method")
            }

            // Capture stdout
            val baos = ByteArrayOutputStream()
            val capturedOut = PrintStream(baos)
            val originalOut = System.out
            val originalErr = System.err

            try {
                System.setOut(capturedOut)
                System.setErr(capturedOut)
                mainMethod.invoke(null, arrayOf<String>())
                capturedOut.flush()
                RunResult.Success(baos.toString())
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        } catch (e: Exception) {
            val cause = e.cause ?: e
            RunResult.Failure("Execution failed: ${cause.message}", cause)
        }
    }

    /**
     * Loads multiple classes from a bytecode map and invokes the main class's main(String[]) method.
     * Captures stdout and returns the result.
     */
    fun run(mainClass: String, bytecodeMap: Map<String, ByteArray>, saveClass: Boolean = false): RunResult {
        if (saveClass) {
            bytecodeMap.forEach { (name, bytes) -> saveClassFile(name, bytes) }
        }

        return try {
            val classLoader = BytecodeClassLoader(bytecodeMap)
            val clazz = classLoader.loadClass(mainClass)

            val mainMethod = try {
                clazz.getMethod("main", Array<String>::class.java)
            } catch (e: NoSuchMethodException) {
                return RunResult.Failure("Class '$mainClass' has no main(String[]) method")
            }

            val baos = ByteArrayOutputStream()
            val capturedOut = PrintStream(baos)
            val originalOut = System.out
            val originalErr = System.err

            try {
                System.setOut(capturedOut)
                System.setErr(capturedOut)
                mainMethod.invoke(null, arrayOf<String>())
                capturedOut.flush()
                RunResult.Success(baos.toString())
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        } catch (e: Exception) {
            val cause = e.cause ?: e
            RunResult.Failure("Execution failed: ${cause.message}", cause)
        }
    }

    private fun saveClassFile(className: String, bytecode: ByteArray) {
        val fileName = "${className.substringAfterLast('.')}.class"
        File(fileName).writeBytes(bytecode)
        System.err.println("[debug] Saved $fileName (${bytecode.size} bytes) — inspect with: javap -c $fileName")
    }
}
