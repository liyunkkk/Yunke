package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini 原生请求（Interactions API 与 Vertex AI generateContent）仅在同一轮工具循环内有效的会话状态。
 *
 * `signature` / `thoughtSignature` 在当前 run 内原样回传，持久化、压缩与跨会话复制时移除。
 */
internal object GeminiEphemeralState {
    private const val STEPS_KEY = "_eta_gemini_steps"
    private const val VERTEX_PARTS_KEY = "_eta_vertex_parts"
    private const val RUN_START_KEY = "_eta_gemini_run_start"
    private const val PENDING_SUGGESTIONS_KEY = "_eta_gemini_pending_suggestions"

    fun markRunStart(message: JSONObject) {
        message.put(RUN_START_KEY, true)
    }

    fun isRunStart(message: JSONObject): Boolean = message.optBoolean(RUN_START_KEY, false)

    fun attachSteps(message: JSONObject, steps: JSONArray) {
        message.put(STEPS_KEY, JSONArray(steps.toString()))
    }

    fun steps(message: JSONObject): JSONArray? = message.optJSONArray(STEPS_KEY)?.let { JSONArray(it.toString()) }

    fun attachVertexParts(message: JSONObject, parts: JSONArray) {
        message.put(VERTEX_PARTS_KEY, JSONArray(parts.toString()))
    }

    fun vertexParts(message: JSONObject): JSONArray? =
        message.optJSONArray(VERTEX_PARTS_KEY)?.let { JSONArray(it.toString()) }

    fun attachPendingSuggestions(message: JSONObject, suggestions: JSONArray) {
        if (suggestions.length() == 0) message.remove(PENDING_SUGGESTIONS_KEY)
        else message.put(PENDING_SUGGESTIONS_KEY, JSONArray(suggestions.toString()))
    }

    fun pendingSuggestions(message: JSONObject): JSONArray =
        message.optJSONArray(PENDING_SUGGESTIONS_KEY)?.let { JSONArray(it.toString()) } ?: JSONArray()

    fun copyAssistantState(source: JSONObject, target: JSONObject) {
        steps(source)?.let { attachSteps(target, it) }
        vertexParts(source)?.let { attachVertexParts(target, it) }
        val suggestions = pendingSuggestions(source)
        if (suggestions.length() > 0) attachPendingSuggestions(target, suggestions)
    }

    fun hasPendingToolResponse(messages: JSONArray): Boolean {
        val last = messages.optJSONObject(messages.length() - 1) ?: return false
        if (last.optString("role") != "tool" && !last.optBoolean("_eta_observation")) return false
        for (i in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") == "assistant") {
                return steps(message) != null || vertexParts(message) != null
            }
        }
        return false
    }

    fun withoutState(message: JSONObject): JSONObject =
        JSONObject(message.toString()).apply {
            remove(STEPS_KEY)
            remove(VERTEX_PARTS_KEY)
            remove(RUN_START_KEY)
            remove(PENDING_SUGGESTIONS_KEY)
        }
}
