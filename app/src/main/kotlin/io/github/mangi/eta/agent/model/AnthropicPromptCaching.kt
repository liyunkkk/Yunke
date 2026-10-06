package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Native Anthropic prefix-cache defaults. This is not a local response cache. */
internal object AnthropicPromptCaching {
    // Local custom-body preference: none, 5m (default), or 1h. Never sent upstream.
    const val CONFIG_KEY = "eta_prompt_cache"
    // Local system-message metadata, used only by the Anthropic prompt builder/adapter.
    const val SYSTEM_BOUNDARY_KEY = "eta_prompt_cache_boundary"

    /**
     * Keep <= 4 explicit breakpoints: tools, stable instructions, complete system,
     * and the final user block (including a normalized tool-result batch).
     * Any caller-supplied cache_control owns the entire policy, including TTL/order.
     */
    fun applyDefaults(request: JSONObject, stableSystemBlockIndex: Int? = null) {
        val preference = request.remove(CONFIG_KEY)
        val retention = if (preference == null) "5m" else preference.toString()
        require(retention in setOf("none", "5m", "1h")) {
            "$CONFIG_KEY must be none, 5m, or 1h"
        }
        if (retention == "none" || hasExplicitControl(request)) return

        val candidates = mutableListOf<JSONObject>()
        val tools = request.optJSONArray("tools")
        if (tools != null && tools.length() > 0) {
            tools.optJSONObject(tools.length() - 1)?.let(candidates::add)
        }
        // A custom system string remains valid input; wrap without changing its text.
        val system = when (val value = request.opt("system")) {
            is JSONArray -> value
            is String -> if (value.isNotBlank()) {
                JSONArray().put(JSONObject().put("type", "text").put("text", value))
                    .also { request.put("system", it) }
            } else null
            else -> null
        }
        if (system != null) {
            stableSystemBlockIndex?.takeIf { it in 0 until system.length() }
                ?.let { system.optJSONObject(it) }
                ?.takeIf(::isCacheableBlock)
                ?.let(candidates::add)
            (system.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
                system.optJSONObject(index)?.takeIf(::isCacheableBlock)
            }?.let(candidates::add)
        }
        val messages = request.optJSONArray("messages")
        val lastMessage = messages?.optJSONObject(messages.length() - 1)
        if (lastMessage?.optString("role") == "user") {
            val content = when (val value = lastMessage.opt("content")) {
                is JSONArray -> value
                is String -> if (value.isNotBlank()) {
                    JSONArray().put(JSONObject().put("type", "text").put("text", value))
                        .also { lastMessage.put("content", it) }
                } else null
                else -> null
            }
            // Only the actual final block: never mark thinking or an empty text block.
            content?.optJSONObject(content.length() - 1)
                ?.takeIf(::isCacheableBlock)
                ?.let(candidates::add)
        }
        val marked = mutableListOf<JSONObject>()
        candidates.forEach { block ->
            if (marked.none { it === block }) {
                val control = JSONObject().put("type", "ephemeral")
                if (retention == "1h") control.put("ttl", "1h")
                block.put("cache_control", control)
                marked.add(block)
            }
        }
    }

    private fun isCacheableBlock(block: JSONObject): Boolean = when (block.optString("type")) {
        "text" -> block.optString("text").isNotBlank()
        "image", "document", "tool_result", "tool_use" -> true
        else -> false
    }

    // Inspect only valid breakpoint locations, not tool schemas or tool-result JSON data.
    private fun hasExplicitControl(request: JSONObject): Boolean {
        if (request.has("cache_control")) return true
        for (key in listOf("tools", "system")) {
            val blocks = request.optJSONArray(key) ?: continue
            for (index in 0 until blocks.length()) {
                if (blocks.optJSONObject(index)?.has("cache_control") == true) return true
            }
        }
        val messages = request.optJSONArray("messages") ?: return false
        for (index in 0 until messages.length()) {
            val blocks = messages.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (blockIndex in 0 until blocks.length()) {
                if (blocks.optJSONObject(blockIndex)?.has("cache_control") == true) return true
            }
        }
        return false
    }
}
