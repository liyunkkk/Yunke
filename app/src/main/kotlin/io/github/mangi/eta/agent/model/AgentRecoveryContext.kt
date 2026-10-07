package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** A runtime-owned, durable constraint. A new model request is not a replay of tool execution. */
internal object AgentRecoveryContext {
    private const val PREFIX = "[Eta safe recovery context v1]"
    private const val INSTRUCTION = PREFIX + " Continue only unfinished work from committed history. " +
        "Preserve completed tool results; never repeat completed operations or failed call fragments. " +
        "Never reuse a historical tool_call_id, including calls with an unknown outcome. " +
        "Hosted/remote tools and opaque remote continuation are unavailable during this recovery. " +
        "A remote operation whose result is unknown may already have executed: do not retry it, " +
        "recreate it, or replace it with an equivalent local/browser/HTTP operation. " +
        "Only read-only verification or independent remaining work is allowed for that uncertainty. " +
        "Never claim an unknown operation succeeded. New, complete local calls for other unfinished work remain available."

    fun isActive(messages: JSONArray): Boolean = (0 until messages.length()).any { index ->
        val message = messages.optJSONObject(index)
        message != null && message.optString("role") == "system" && message.optString("content") == INSTRUCTION
    }

    fun append(messages: JSONArray) {
        if (!isActive(messages)) messages.put(JSONObject().put("role", "system").put("content", INSTRUCTION))
    }
}
