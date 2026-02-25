package dev.byteforge.llm

import kotlinx.serialization.json.*

/**
 * Manages multi-turn conversation history for Claude API interactions.
 * Tracks messages for self-repair loops and conversational iteration.
 */
class ConversationManager {
    private val messages = mutableListOf<JsonObject>()

    fun getMessages(): List<JsonObject> = messages.toList()

    fun addUserMessage(content: String) {
        messages.add(buildJsonObject {
            put("role", "user")
            put("content", content)
        })
    }

    fun addUserMessage(content: JsonArray) {
        messages.add(buildJsonObject {
            put("role", "user")
            put("content", content)
        })
    }

    fun addAssistantResponse(content: JsonArray) {
        messages.add(buildJsonObject {
            put("role", "assistant")
            put("content", content)
        })
    }

    /**
     * Add a tool_result message. After Claude returns tool_use, the next user message
     * must contain a tool_result with matching tool_use_id.
     * Setting isError=true tells Claude the tool execution failed.
     */
    fun addToolResult(toolUseId: String, content: String, isError: Boolean = false) {
        val toolResultBlock = buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", toolUseId)
            put("content", content)
            if (isError) put("is_error", true)
        }
        messages.add(buildJsonObject {
            put("role", "user")
            put("content", JsonArray(listOf(toolResultBlock)))
        })
    }

    fun trimToLast(n: Int) {
        if (messages.size > n) {
            val keep = messages.takeLast(n)
            messages.clear()
            messages.addAll(keep)
        }
    }

    fun clear() {
        messages.clear()
    }

    val size: Int get() = messages.size
}
