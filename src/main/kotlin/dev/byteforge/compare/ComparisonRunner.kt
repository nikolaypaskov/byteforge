package dev.byteforge.compare

import java.io.File
import java.nio.file.Files

data class ComparisonResult(
    val javacJavap: String,
    val byteforgeJavap: String,
    val javacSize: Int,
    val byteforgeSize: Int,
    val sizeDiffPercent: Double,
)

object ComparisonRunner {

    /**
     * Compare ByteForge bytecode with javac output for equivalent Java source.
     */
    fun compare(javaSource: String, byteforgeBytes: ByteArray, className: String): ComparisonResult {
        val tempDir = Files.createTempDirectory("byteforge-compare").toFile()
        try {
            // Write and compile Java source
            val javaFile = File(tempDir, "$className.java")
            javaFile.writeText(javaSource)

            val javacProcess = ProcessBuilder("javac", javaFile.absolutePath)
                .directory(tempDir)
                .redirectErrorStream(true)
                .start()
            val javacOutput = javacProcess.inputStream.bufferedReader().readText()
            val javacExitCode = javacProcess.waitFor()
            if (javacExitCode != 0) {
                throw RuntimeException("javac failed (exit $javacExitCode):\n$javacOutput")
            }

            val javacClassFile = File(tempDir, "$className.class")
            val javacSize = javacClassFile.length().toInt()

            // Run javap on javac output
            val javapJavacProcess = ProcessBuilder("javap", "-c", "-p", javacClassFile.absolutePath)
                .directory(tempDir)
                .redirectErrorStream(true)
                .start()
            val javacJavap = javapJavacProcess.inputStream.bufferedReader().readText()
            javapJavacProcess.waitFor()

            // Write ByteForge bytecode and run javap on it
            val bfDir = File(tempDir, "byteforge")
            bfDir.mkdirs()
            val bfClassFile = File(bfDir, "$className.class")
            bfClassFile.writeBytes(byteforgeBytes)
            val byteforgeSize = byteforgeBytes.size

            val javapBfProcess = ProcessBuilder("javap", "-c", "-p", bfClassFile.absolutePath)
                .directory(bfDir)
                .redirectErrorStream(true)
                .start()
            val byteforgeJavap = javapBfProcess.inputStream.bufferedReader().readText()
            javapBfProcess.waitFor()

            val sizeDiff = if (javacSize > 0) {
                ((byteforgeSize - javacSize).toDouble() / javacSize) * 100.0
            } else 0.0

            return ComparisonResult(
                javacJavap = javacJavap,
                byteforgeJavap = byteforgeJavap,
                javacSize = javacSize,
                byteforgeSize = byteforgeSize,
                sizeDiffPercent = sizeDiff,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
