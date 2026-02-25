package dev.byteforge

import dev.byteforge.llm.ConversationManager
import kotlinx.serialization.json.*

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
    println("=== ConversationManager Tests ===")
    println()

    // 1. addUserMessage(String)
    test("addUserMessage(String) sets role and content") {
        val cm = ConversationManager()
        cm.addUserMessage("hello")
        val msg = cm.getMessages().first()
        assert(msg["role"]?.jsonPrimitive?.content == "user") { "Expected role 'user'" }
        assert(msg["content"]?.jsonPrimitive?.content == "hello") { "Expected content 'hello'" }
    }

    // 2. addUserMessage(JsonArray)
    test("addUserMessage(JsonArray) sets role and content array") {
        val cm = ConversationManager()
        val arr = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "hi") })
        }
        cm.addUserMessage(arr)
        val msg = cm.getMessages().first()
        assert(msg["role"]?.jsonPrimitive?.content == "user") { "Expected role 'user'" }
        assert(msg["content"] is JsonArray) { "Expected content to be JsonArray" }
        val contentArr = msg["content"]!!.jsonArray
        assert(contentArr.size == 1) { "Expected 1 element in content array" }
    }

    // 3. addAssistantResponse
    test("addAssistantResponse sets role to assistant") {
        val cm = ConversationManager()
        val arr = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "response") })
        }
        cm.addAssistantResponse(arr)
        val msg = cm.getMessages().first()
        assert(msg["role"]?.jsonPrimitive?.content == "assistant") { "Expected role 'assistant'" }
        assert(msg["content"] is JsonArray) { "Expected content to be JsonArray" }
    }

    // 4. addToolResult isError=false
    test("addToolResult isError=false has no is_error key") {
        val cm = ConversationManager()
        cm.addToolResult("tool-123", "result data")
        val msg = cm.getMessages().first()
        assert(msg["role"]?.jsonPrimitive?.content == "user") { "Expected role 'user'" }
        val contentArr = msg["content"]!!.jsonArray
        val toolResult = contentArr[0].jsonObject
        assert(toolResult["type"]?.jsonPrimitive?.content == "tool_result") { "Expected type 'tool_result'" }
        assert(toolResult["tool_use_id"]?.jsonPrimitive?.content == "tool-123") { "Expected tool_use_id 'tool-123'" }
        assert(toolResult["content"]?.jsonPrimitive?.content == "result data") { "Expected content 'result data'" }
        assert(!toolResult.containsKey("is_error")) { "Expected no 'is_error' key when isError=false" }
    }

    // 5. addToolResult isError=true
    test("addToolResult isError=true has is_error=true") {
        val cm = ConversationManager()
        cm.addToolResult("tool-456", "error msg", isError = true)
        val msg = cm.getMessages().first()
        val contentArr = msg["content"]!!.jsonArray
        val toolResult = contentArr[0].jsonObject
        assert(toolResult.containsKey("is_error")) { "Expected 'is_error' key when isError=true" }
        assert(toolResult["is_error"]?.jsonPrimitive?.boolean == true) { "Expected is_error=true" }
    }

    // 6. trimToLast keeps last n messages
    test("trimToLast keeps last n messages") {
        val cm = ConversationManager()
        for (i in 1..5) cm.addUserMessage("msg$i")
        cm.trimToLast(2)
        assert(cm.size == 2) { "Expected size 2 after trimToLast(2), got ${cm.size}" }
        val msgs = cm.getMessages()
        assert(msgs[0]["content"]?.jsonPrimitive?.content == "msg4") { "Expected first kept message to be 'msg4'" }
        assert(msgs[1]["content"]?.jsonPrimitive?.content == "msg5") { "Expected second kept message to be 'msg5'" }
    }

    // 7. trimToLast no-op when n >= size
    test("trimToLast no-op when n >= size") {
        val cm = ConversationManager()
        cm.addUserMessage("a")
        cm.addUserMessage("b")
        cm.trimToLast(5)
        assert(cm.size == 2) { "Expected size 2 after trimToLast(5), got ${cm.size}" }
    }

    // 8. clear empties all messages
    test("clear empties all messages") {
        val cm = ConversationManager()
        cm.addUserMessage("a")
        cm.addUserMessage("b")
        cm.clear()
        assert(cm.size == 0) { "Expected size 0 after clear(), got ${cm.size}" }
        assert(cm.getMessages().isEmpty()) { "Expected empty messages list" }
    }

    // 9. size property increments correctly
    test("size property increments correctly") {
        val cm = ConversationManager()
        assert(cm.size == 0) { "Expected initial size 0" }
        cm.addUserMessage("a")
        assert(cm.size == 1) { "Expected size 1 after one message" }
        cm.addUserMessage("b")
        assert(cm.size == 2) { "Expected size 2 after two messages" }
        val arr = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "c") }) }
        cm.addAssistantResponse(arr)
        assert(cm.size == 3) { "Expected size 3 after three messages" }
    }

    // 10. getMessages returns a copy
    test("getMessages returns a copy (original unchanged after modifying returned list)") {
        val cm = ConversationManager()
        cm.addUserMessage("original")
        val copy = cm.getMessages().toMutableList()
        copy.clear()
        assert(cm.size == 1) { "Expected original size still 1, got ${cm.size}" }
        assert(cm.getMessages().size == 1) { "Expected getMessages() still returns 1 message" }
    }

    // 11. Message ordering preserved
    test("Message ordering preserved") {
        val cm = ConversationManager()
        cm.addUserMessage("first")
        val assistantArr = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "second") }) }
        cm.addAssistantResponse(assistantArr)
        cm.addUserMessage("third")
        val msgs = cm.getMessages()
        assert(msgs.size == 3) { "Expected 3 messages" }
        assert(msgs[0]["role"]?.jsonPrimitive?.content == "user") { "Expected first message role 'user'" }
        assert(msgs[0]["content"]?.jsonPrimitive?.content == "first") { "Expected first message content 'first'" }
        assert(msgs[1]["role"]?.jsonPrimitive?.content == "assistant") { "Expected second message role 'assistant'" }
        assert(msgs[2]["role"]?.jsonPrimitive?.content == "user") { "Expected third message role 'user'" }
        assert(msgs[2]["content"]?.jsonPrimitive?.content == "third") { "Expected third message content 'third'" }
    }

    println()
    println("=== Results: $passed/$testNum passed ===")
    if (passed < testNum) {
        System.exit(1)
    }
}
