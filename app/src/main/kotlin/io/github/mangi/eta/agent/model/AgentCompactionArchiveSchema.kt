package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Reject lossy opt* coercion before decoding durable archive messages. */
internal object AgentCompactionArchiveSchema {
    private val roles = setOf("system", "developer", "user", "assistant", "tool", "function")

    fun validateMessage(message: JSONObject) {
        val role = message.opt("role")
        require(role is String && role in roles) { "归档消息 role 无效" }
        val content = message.opt("content")
        require(content is String || content is JSONArray || content is JSONObject) { "归档消息 content 无效" }
        for (field in listOf("tool_call_id", AgentTurnIdentity.JSON_KEY, "reasoning_content")) {
            require(!message.has(field) || message.opt(field) is String) { "归档消息 $field 类型无效" }
        }
        if (message.has("tool_calls")) {
            val calls = message.opt("tool_calls")
            require(calls is JSONArray) { "归档 tool_calls 不是数组" }
            for (index in 0 until calls.length()) {
                require(calls.opt(index) is JSONObject) { "归档工具调用不是对象" }
            }
        }
        if (message.has("reasoning")) {
            val reasoning = message.opt("reasoning")
            require(reasoning is String || reasoning is JSONObject) { "归档 reasoning 类型无效" }
            if (reasoning is JSONObject) {
                for (field in listOf("content", "text")) {
                    require(!reasoning.has(field) || reasoning.opt(field) is String) { "归档推理正文类型无效" }
                }
            }
        }
        if (message.has(ResponsesReasoningState.KEY)) {
            val reasoning = message.opt(ResponsesReasoningState.KEY)
            require(reasoning is JSONObject && ResponsesReasoningState.sanitize(reasoning.toString()).isNotBlank()) {
                "归档 Responses 推理状态无效"
            }
        }
    }
}
