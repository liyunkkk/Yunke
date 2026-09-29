package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AntigravityRequestBuilder {
    fun build(config: AgentModelClient.ModelConfig, messages: JSONArray, tools: JSONArray, projectId: String, sessionId: String = ""): JSONObject {
        val contents = JSONArray()
        val systemParts = JSONArray()
        val callNames = linkedMapOf<String, String>()
        if (config.systemPrompt.isNotBlank()) {
            systemParts.put(JSONObject().put("text", config.systemPrompt))
        }
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "system", "developer" -> {
                    val text = message.optString("content")
                    if (text.isNotBlank()) systemParts.put(JSONObject().put("text", text))
                }
                "user" -> contents.put(JSONObject().put("role", "user").put("parts", userParts(message)))
                "assistant" -> {
                    val parts = JSONArray()
                    val text = message.optString("content")
                    if (text.isNotBlank() && text != "null") parts.put(JSONObject().put("text", text))
                    val calls = message.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (i in 0 until calls.length()) {
                            val call = calls.optJSONObject(i) ?: continue
                            val fn = call.optJSONObject("function") ?: continue
                            val name = fn.optString("name")
                            val id = call.optString("id").ifBlank { "tool_call_$i" }
                            callNames[id] = name
                            val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                            parts.put(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", args)))
                        }
                    }
                    if (parts.length() > 0) contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                "tool" -> {
                    val callId = message.optString("tool_call_id")
                    val name = callNames[callId] ?: callId.ifBlank { "tool" }
                    val response = JSONObject().put("result", message.optString("content"))
                    contents.put(
                        JSONObject().put("role", "user").put(
                            "parts",
                            JSONArray().put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", response))),
                        ),
                    )
                }
            }
        }
        val request = JSONObject()
        if (projectId.isNotBlank()) request.put("project", projectId)
        request.put("model", config.model)
        request.put("userAgent", "antigravity")
        request.put("requestType", "agent")
        request.put("requestId", "agent-" + java.util.UUID.randomUUID().toString())
        val inner = JSONObject().put("contents", contents)
        if (sessionId.isNotBlank()) inner.put("sessionId", sessionId)
        if (systemParts.length() > 0) inner.put("systemInstruction", JSONObject().put("parts", systemParts))
        val declarations = functionDeclarations(tools)
        if (declarations.length() > 0) {
            inner.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
        }
        val generation = JSONObject()
        config.summaryOutputLimit?.let { generation.put("maxOutputTokens", it) }
        if (config.effectiveReasoningEffort.enablesReasoning) {
            generation.put("thinkingConfig", JSONObject().put("includeThoughts", true).put("thinkingBudget", 8192))
        }
        if (generation.length() > 0) inner.put("generationConfig", generation)
        request.put("request", inner)
        return request
    }

    private fun userParts(message: JSONObject): JSONArray {
        val parts = JSONArray()
        val raw = message.opt("content")
        if (raw is String) {
            if (raw.isNotBlank()) parts.put(JSONObject().put("text", raw))
            return parts
        }
        val source = raw as? JSONArray ?: return parts
        for (index in 0 until source.length()) {
            val part = source.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "text", "input_text" -> parts.put(JSONObject().put("text", part.optString("text")))
                "image_url", "input_image" -> {
                    val url = part.optJSONObject("image_url")?.optString("url") ?: part.optString("image_url")
                    if (url.startsWith("data:")) {
                        val header = url.substringAfter("data:").substringBefore(",")
                        val mime = header.substringBefore(";").ifBlank { "image/png" }
                        val data = url.substringAfter(",")
                        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data)))
                    } else if (url.isNotBlank()) {
                        parts.put(JSONObject().put("text", url))
                    }
                }
            }
        }
        return parts
    }

    private fun functionDeclarations(tools: JSONArray): JSONArray {
        val result = JSONArray()
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            result.put(
                JSONObject()
                    .put("name", function.optString("name"))
                    .put("description", function.optString("description"))
                    .put("parameters", sanitizeSchema(function.optJSONObject("parameters") ?: JSONObject().put("type", "object"))),
            )
        }
        return result
    }

    internal fun sanitizeSchema(raw: Any?): Any = when (raw) {
        is JSONObject -> sanitizeObject(raw)
        is JSONArray -> JSONArray().also { array ->
            for (index in 0 until raw.length()) array.put(sanitizeSchema(raw.opt(index)))
        }
        else -> raw ?: JSONObject.NULL
    }

    private fun sanitizeObject(source: JSONObject): JSONObject {
        val result = JSONObject()
        source.keys().forEach { key ->
            if (key in DROPPED_SCHEMA_KEYS) return@forEach
            result.put(key, sanitizeSchema(source.opt(key)))
        }
        if (!result.has("type") && (result.has("properties") || result.has("required"))) {
            result.put("type", "object")
        }
        return result
    }

    private val DROPPED_SCHEMA_KEYS = setOf(
        "uniqueItems",
        "additionalProperties",
        "additional_properties",
        "minItems",
        "maxItems",
        "minLength",
        "maxLength",
        "minimum",
        "maximum",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "multipleOf",
        "pattern",
        "default",
        "examples",
        "example",
        "const",
        "$" + "schema",
        "$" + "id",
        "$" + "ref",
        "$" + "defs",
        "definitions",
        "oneOf",
        "anyOf",
        "allOf",
        "not",
        "if",
        "then",
        "else",
        "dependentRequired",
        "dependentSchemas",
        "unevaluatedProperties",
        "unevaluatedItems",
        "prefixItems",
        "contains",
        "propertyNames",
        "contentEncoding",
        "contentMediaType",
        "title",
        "deprecated",
        "readOnly",
        "writeOnly",
    )
}
