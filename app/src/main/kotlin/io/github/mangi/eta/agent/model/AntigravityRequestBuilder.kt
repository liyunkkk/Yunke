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
        val isImageModel = config.model.contains("image", ignoreCase = true)
        val request = JSONObject()
        if (projectId.isNotBlank()) request.put("project", projectId)
        request.put("model", AntigravityModelRouting.runtimeModelId(config.model))
        request.put("userAgent", "antigravity")
        request.put("requestType", if (isImageModel) "image_gen" else "agent")
        request.put("requestId", if (isImageModel) {
            "image_gen/" + System.currentTimeMillis() + "/" + java.util.UUID.randomUUID() + "/12"
        } else {
            "agent-" + java.util.UUID.randomUUID().toString()
        })
        val inner = JSONObject().put("contents", contents)
        if (!isImageModel) inner.put("sessionId", deriveSessionId(contents, sessionId))
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

    /**
     * 原生会话指纹：由首条用户消息派生稳定的负数会话 id。
     *
     * 意图：Cloud Code 按负数 sessionId 把多轮请求归并为同一会话。
     * 不这么做后果：随机或通用会话 id 无法被上游关联，长上下文续轮会被当成新会话而断链。
     * 来源：与 CLIProxyAPI / PR #1257 的指纹集对齐。
     */
    internal fun deriveSessionId(contents: JSONArray, fallback: String = ""): String {
        for (index in 0 until contents.length()) {
            val content = contents.optJSONObject(index) ?: continue
            if (content.optString("role") != "user") continue
            val parts = content.optJSONArray("parts") ?: continue
            for (partIndex in 0 until parts.length()) {
                val text = parts.optJSONObject(partIndex)?.optString("text").orEmpty()
                if (text.isBlank()) continue
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                var value = 0L
                for (byteIndex in 0 until 8) {
                    value = (value shl 8) or (digest[byteIndex].toLong() and 0xFFL)
                }
                return "-" + (value and 0x7FFFFFFFFFFFFFFFL)
            }
        }
        return fallback.takeIf { it.isNotBlank() }
            ?: ("-" + (java.security.SecureRandom().nextLong() and 0x7FFFFFFFFFFFFFFFL))
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
        val rawRequired = source.optJSONArray("required")
        source.keys().forEach { key ->
            if (key in DROPPED_SCHEMA_KEYS) return@forEach
            // required 单独处理：见下方悬空名字裁剪。
            if (key == "required") return@forEach
            // properties 的键是参数名而不是 schema 关键字，不能按关键字丢弃：
            // 丢掉会让该参数对模型不可见，且 required 里的名字随即变成悬空。
            if (key == "properties") {
                val properties = source.optJSONObject(key)
                result.put(
                    key,
                    if (properties == null) sanitizeSchema(source.opt(key)) else sanitizeProperties(properties),
                )
                return@forEach
            }
            result.put(key, sanitizeSchema(source.opt(key)))
        }
        if (!result.has("type") && (result.has("properties") || rawRequired != null)) {
            result.put("type", "object")
        }
        // CCA/Gemini 要求 required 里的每个名字都在 properties 中有定义，否则整条请求被拒：
        // "GenerateContentRequest.tools[i].function_declarations[j].parameters.required[k]:
        //  property is not defined"。工具 schema 可能带悬空名字（典型来源是定义在
        // 被裁掉的 oneOf/anyOf/allOf 分支里的属性），这里裁掉；裁空则整个 required 不再发送。
        if (rawRequired != null) {
            val properties = result.optJSONObject("properties")
            val kept = JSONArray()
            val seen = linkedSetOf<String>()
            for (index in 0 until rawRequired.length()) {
                val name = rawRequired.optString(index)
                if (name.isBlank() || properties?.has(name) != true || !seen.add(name)) continue
                kept.put(name)
            }
            if (kept.length() > 0) result.put("required", kept)
        }
        return result
    }

    /** properties 映射：键是参数名，值才是子 schema，因此键不做关键字过滤。 */
    private fun sanitizeProperties(source: JSONObject): JSONObject {
        val result = JSONObject()
        source.keys().forEach { name -> result.put(name, sanitizeSchema(source.opt(name))) }
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
