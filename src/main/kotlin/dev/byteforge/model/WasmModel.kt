package dev.byteforge.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class WasmType {
    @SerialName("i32") I32,
    @SerialName("i64") I64,
    @SerialName("f32") F32,
    @SerialName("f64") F64,
}

@Serializable
data class WasmImport(
    val module: String,
    val name: String,
    val kind: String, // "func", "memory"
    val params: List<WasmType> = emptyList(),
    val results: List<WasmType> = emptyList(),
    val funcName: String? = null, // name to bind in the module
    val memoryMin: Int? = null,
    val memoryMax: Int? = null,
)

@Serializable
data class WasmExport(
    val name: String,
    val kind: String, // "func", "memory"
    val ref: String, // function or memory name
)

@Serializable
data class WasmInstruction(
    val op: String,
    val value: String? = null, // for i32.const, call, etc.
)

@Serializable
data class WasmFunction(
    val name: String,
    val params: List<WasmType> = emptyList(),
    val results: List<WasmType> = emptyList(),
    val locals: List<WasmType> = emptyList(),
    val instructions: List<WasmInstruction>,
)

@Serializable
data class WasmDataSegment(
    val offset: Int,
    val data: String, // the string data
)

@Serializable
data class WasmModule(
    val imports: List<WasmImport> = emptyList(),
    val exports: List<WasmExport> = emptyList(),
    val functions: List<WasmFunction>,
    val data: List<WasmDataSegment> = emptyList(),
    val memoryPages: Int = 1,
)
