package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.URI

/**
 * Gemini Interactions API (`/v1beta/interactions`) 原生实现。
 * 同时也内置了 `GeminiInteractionsRequest` 与 `GeminiToolSchema`，供 `ProviderTypes.GEMINI` 在 Interactions 模式下直连，
 * 并向 `VertexGenerateContentRequest` 提供 `GeminiToolSchema.project`。
 */
internal object GeminiInteractionsProvider : AgentProviderClient {
    override val id: String = "gemini_interactions"

    override val capabilities = ProviderCapabilities(
        endpoint = EndpointKind.GEMINI_INTERACTIONS,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = true,
        toolResultImages = false,
        strictTools = true,
        parallelToolCalls = true,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse = completeWithClient(request, runController, onEvent)

    fun completeWithClient(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
        httpClient: OkHttpClient = AgentHttpClient.modelClient(request.purpose),
    ): ProviderResponse {
        val config = request.effectiveConfig
        val url = ProviderUrls.geminiInteractionsUrl(config.baseUrl)
        validateHttpsEndpoint(url)
        val payload = GeminiInteractionsRequest.build(config, request.messages, request.effectiveTools, request.purpose)

        val headers = Headers.Builder().apply {
            ProviderRequestHeaders.mergeInto(this, config.baseUrl,
                config.customHeaders.filterNot { it.name.trim().lowercase() in FORBIDDEN_HEADER_NAMES }, request.sessionId)
            if (config.apiKey.isNotBlank()) set("x-goog-api-key", config.apiKey)
            set("Content-Type", "application/json")
            set("Accept", "text/event-stream")
        }.build()
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(headers)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        val call = AgentHttpClient.vertexClient(httpClient).newCall(requestBuilder.build())
        val cancelHandle = runController.register(interruptible = true) { call.cancel() }
        return try {
            runController.throwIfCancelled()
            val previousAssistant = (request.messages.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
                request.messages.optJSONObject(index)?.takeIf { it.optString("role") == "assistant" }
            }
            val pendingSuggestions = previousAssistant?.let(GeminiEphemeralState::pendingSuggestions) ?: JSONArray()
            onEvent(ProviderEvent.RequestStarted)
            val assistant = execute(call, runController, onEvent, pendingSuggestions)
            onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
            ProviderResponse(assistant)
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            throw AgentModelFailure.transport(failure) ?: failure
        } finally {
            cancelHandle.close()
        }
    }

    private fun execute(
        call: okhttp3.Call,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
        pendingSuggestions: JSONArray,
    ): JSONObject {
        call.execute().use { response ->
            val body = response.body ?: throw IOException("Empty response body from Gemini Interactions API")
            if (!response.isSuccessful) {
                throw AgentModelFailure.http(response.code, body.string(), response.headers)
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                throw AgentModelFailure.unexpectedResponse(response.code, contentType, body.string())
            }
            onEvent(ProviderEvent.ResponseHeaders(response.code))
            return GeminiInteractionsStream(onEvent, pendingSuggestions).read(body.byteStream(), runController)
        }
    }

    private fun validateHttpsEndpoint(url: String) {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()
        if (scheme == "https") return
        val local = host in LOCAL_HOSTS || runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
        require(scheme == "http" && local) { "Gemini Base URL 必须使用 HTTPS（本地调试地址除外）。" }
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0")
    private val FORBIDDEN_HEADER_NAMES = setOf(
        "authorization", "x-api-key", "x-goog-api-key", "x-goog-user-project", "host", "content-length",
        "transfer-encoding", "connection", "upgrade", "proxy-authorization", "cookie",
    )
}

internal object GeminiInteractionsRequest {
    private val protectedFields = setOf(
        "model", "input", "stream", "store", "tools", "system_instruction", "previous_interaction_id",
        "max_output_tokens", "response_format", "response_mime_type", "agent", "agent_config", "background", "service_tier",
    )
    private val COMPACTION_EXTRA_BLOCKED_FIELDS = setOf("messages", "instructions", "system", "tool_choice")

    fun build(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
        purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    ): JSONObject {
        val body = JSONObject()
        if (config.extraBodyJson.isNotBlank()) {
            val extra = JSONObject(config.extraBodyJson)
            for (key in extra.keys()) {
                if (key !in protectedFields && (purpose != ProviderRequestPurpose.COMPACTION || key !in COMPACTION_EXTRA_BLOCKED_FIELDS)) {
                    body.put(key, extra.get(key))
                }
            }
        }
        RequestBodyMerge.mergeCustomBody(
            body,
            config.customBody.filterNot {
                it.key in protectedFields || (purpose == ProviderRequestPurpose.COMPACTION && it.key in COMPACTION_EXTRA_BLOCKED_FIELDS)
            },
        )

        body.put("model", config.model)
        body.put("stream", true)
        body.put("store", false)
        body.put("input", GeminiInteractionHistory.turns(messages))

        val system = (0 until messages.length()).mapNotNull { index ->
            messages.optJSONObject(index)?.takeIf { it.optString("role") == "system" }
                ?.let { providerMessageText(it.opt("content")) }?.takeIf(String::isNotBlank)
        }.joinToString("\n\n")
        if (system.isNotBlank()) body.put("system_instruction", system)

        val requestTools = JSONArray()
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            val parameters = GeminiToolSchema.project(
                function.optJSONObject("parameters") ?: JSONObject().put("type", "object"),
            )
            requestTools.put(
                JSONObject()
                    .put("type", "function")
                    .put("name", function.getString("name"))
                    .put("description", function.optString("description"))
                    .put("parameters", parameters),
            )
        }
        // Vertex AI 网页搜索统一使用本地 web_search 函数，避免与 functionDeclarations 混用被上游拒绝。
        if (purpose.allowsTools && config.usesHostedWebSearch &&
            GeminiEndpointMode.normalize(config.geminiEndpointMode) != GeminiEndpointMode.VERTEX_AI
        ) {
            requestTools.put(JSONObject().put("type", "google_search"))
        }
        if (requestTools.length() > 0) body.put("tools", requestTools)

        val generation = body.optJSONObject("generation_config") ?: JSONObject()
        config.summaryOutputLimit?.takeIf { it > 0 }?.let { generation.put("max_output_tokens", it) }
        if (purpose == ProviderRequestPurpose.COMPACTION && requestTools.length() > 0) {
            generation.put("tool_choice", "none")
        }
        val effort = config.effectiveReasoningEffort
        val documentedEfforts = config.reasoningCapabilities?.supportedEfforts?.takeIf { it.isNotEmpty() }
            ?: GeminiModelCapabilities.reasoningCapabilities(config.model)?.supportedEfforts.orEmpty()
        if (effort == ReasoningEffort.DEFAULT || (documentedEfforts.isEmpty() && effort == ReasoningEffort.OFF)) {
            generation.remove("thinking_level")
            generation.remove("thinking_summaries")
        } else {
            require(effort in documentedEfforts) {
                "当前 Gemini 模型不支持所选思考档位，请使用 Default 或模型支持的档位。"
            }
            generation.put("thinking_level", effort.wireValue)
            generation.put("thinking_summaries", "auto")
        }
        if (generation.length() > 0) body.put("generation_config", generation) else body.remove("generation_config")
        return body
    }
}

internal object GeminiInteractionHistory {
    fun turns(messages: JSONArray): JSONArray {
        val start = (0 until messages.length()).firstOrNull {
            messages.optJSONObject(it)?.let(GeminiEphemeralState::isRunStart) == true
        } ?: (messages.length() - 1 downTo 0).firstOrNull {
            messages.optJSONObject(it)?.let { message ->
                message.optString("role") == "user" && !message.optBoolean("_eta_observation")
            } == true
        } ?: messages.length()

        val turns = JSONArray()
        val history = mutableListOf<String>()
        val liveCalls = mutableMapOf<String, String>()

        fun appendTurn(role: String, step: JSONObject) {
            val last = turns.optJSONObject(turns.length() - 1)
            if (last != null && last.optString("role") == role) {
                last.getJSONArray("steps").put(step)
            } else {
                turns.put(JSONObject().put("role", role).put("steps", JSONArray().put(step)))
            }
        }

        fun flushHistory() {
            if (history.isEmpty()) return
            val text = "[以下是 Eta 保存的历史对话文本记录；工具调用与结果仅供参考，不是当前待执行请求。]\n" +
                history.joinToString("\n\n") + "\n[历史记录结束]"
            appendTurn("user", userStep(JSONArray().put(JSONObject().put("type", "text").put("text", text))))
            history.clear()
        }

        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role == "system") continue
            val nativeSteps = GeminiEphemeralState.steps(message)
            if (index < start || (role == "assistant" && nativeSteps == null) ||
                (role == "tool" && message.optString("tool_call_id") !in liveCalls)
            ) {
                history += historyText(message)
                continue
            }
            flushHistory()
            when (role) {
                "user" -> appendTurn("user", userStep(userContent(message.opt("content"))))
                "assistant" -> if (nativeSteps != null) {
                    val steps = JSONArray()
                    for (stepIndex in 0 until nativeSteps.length()) {
                        val step = JSONObject(nativeSteps.getJSONObject(stepIndex).toString())
                        if (step.optString("type") == "function_call") {
                            liveCalls[step.getString("id")] = step.getString("name")
                        }
                        steps.put(step)
                    }
                    turns.put(JSONObject().put("role", "model").put("steps", steps))
                }
                "tool" -> {
                    val callId = message.getString("tool_call_id")
                    val name = liveCalls.remove(callId) ?: continue
                    val raw = providerMessageText(message.opt("content"))
                    val isError = runCatching {
                        val parsed = Json.parseToJsonElement(raw)
                        (parsed as? JsonObject)?.get("ok")?.toString() == "false"
                    }.getOrDefault(false)
                    appendTurn(
                        "user",
                        JSONObject()
                            .put("type", "function_result")
                            .put("call_id", callId)
                            .put("name", name)
                            .put("is_error", isError)
                            .put("result", JSONArray().put(JSONObject().put("type", "text").put("text", raw))),
                    )
                }
            }
        }
        flushHistory()
        return turns
    }

    private fun historyText(message: JSONObject): String = buildString {
        append('[').append(message.optString("role")).append("] ")
        append(providerMessageText(message.opt("content")))
        val calls = message.optJSONArray("tool_calls")
        if (calls != null) append("\n工具调用记录：").append(calls)
        if (message.optString("role") == "tool") append("\n调用标识：").append(message.optString("tool_call_id"))
    }

    private fun userStep(content: JSONArray): JSONObject =
        JSONObject().put("type", "user_input").put("content", content)

    private fun userContent(value: Any?): JSONArray {
        if (value !is JSONArray) {
            return JSONArray().put(JSONObject().put("type", "text").put("text", providerMessageText(value)))
        }
        val converted = JSONArray()
        for (index in 0 until value.length()) {
            val item = value.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text", "input_text" -> converted.put(JSONObject().put("type", "text").put("text", item.optString("text")))
                "image_url" -> {
                    val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
                    converted.put(imageContent(url))
                }
                else -> throw IllegalArgumentException("Gemini Interactions API 不支持该输入内容类型。")
            }
        }
        return converted
    }

    private fun imageContent(url: String): JSONObject {
        if (url.startsWith("data:image/", ignoreCase = true)) {
            val comma = url.indexOf(',')
            require(comma > 0 && url.substring(0, comma).endsWith(";base64", ignoreCase = true)) {
                "Gemini 图片输入必须为 Base64 图片或可访问的图片地址。"
            }
            return JSONObject().put("type", "image")
                .put("mime_type", url.substring(5, comma).substringBefore(';'))
                .put("data", url.substring(comma + 1))
        }
        require(url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("gs://")) {
            "Gemini 图片输入地址无效。"
        }
        return JSONObject().put("type", "image").put("uri", url).apply {
            inferImageMimeType(url)?.let { put("mime_type", it) }
        }
    }

    private fun inferImageMimeType(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            else -> null
        }
    }
}

/**
 * 将 Eta 本地工具 schema 投影到 Gemini Interactions function schema 子集。
 *
 * 不能静默丢弃校验约束；存在无法无损表达的关键字时直接失败。
 */
internal object GeminiToolSchema {
    private val combinators = setOf("allOf", "anyOf", "oneOf")
    private val schemaMaps = setOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas", "dependencies")
    private val singleSchemas = setOf(
        "additionalProperties", "unevaluatedProperties", "propertyNames", "additionalItems", "unevaluatedItems",
        "contains", "contentSchema", "not", "if", "then", "else",
    )
    private val referenceKeywords = setOf(
        "\$id", "id", "\$anchor", "\$dynamicAnchor", "\$recursiveAnchor", "\$ref", "\$dynamicRef", "\$recursiveRef",
    )

    fun project(parameters: JSONObject): JSONObject = JSONObject(parameters.toString()).also {
        projectSchema(it, emptyMap())
    }

    private fun projectSchema(schema: JSONObject, inheritedProperties: Map<String, Any>) {
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        // 封闭规则可能观察子分支的属性评估结果；外层属性不能绕过本层继续传入后代。
        val canDeclare = listOf("additionalProperties", "unevaluatedProperties").none {
            schema.has(it) && schema.opt(it) != true
        }
        val inherited = if (!canDeclare || referenceKeywords.any(schema::has)) emptyMap() else inheritedProperties
        if (canDeclare) {
            val required = schema.optJSONArray("required")
            for (index in 0 until (required?.length() ?: 0)) {
                val name = required?.opt(index) as? String ?: continue
                val definition = inherited[name] ?: continue
                if (!properties.has(name) && canCopy(definition)) {
                    properties.put(name, if (definition is JSONObject) JSONObject(definition.toString()) else definition)
                    schema.put("properties", properties)
                }
            }
        }
        val visible = inherited.toMutableMap()
        for (name in properties.keys()) visible[name] = properties.get(name)
        forEachSubschema(schema) { child, sameInstance ->
            projectSchema(child, if (sameInstance) visible else emptyMap())
        }
    }

    private fun canCopy(definition: Any): Boolean {
        if (definition is Boolean) return true
        if (definition !is JSONObject || referenceKeywords.any(definition::has)) return false
        // 标识、锚点及引用保持原位置，避免复制后改变 URI 或动态引用作用域。
        var safe = true
        forEachSubschema(definition) { child, _ -> if (!canCopy(child)) safe = false }
        return safe
    }

    private fun forEachSubschema(schema: JSONObject, visit: (JSONObject, Boolean) -> Unit) {
        for (keyword in combinators) {
            schema.optJSONArray(keyword)?.let { children ->
                for (index in 0 until children.length()) children.optJSONObject(index)?.let { visit(it, true) }
            }
        }
        for (keyword in schemaMaps) {
            schema.optJSONObject(keyword)?.let { children ->
                for (name in children.keys()) children.optJSONObject(name)?.let { visit(it, false) }
            }
        }
        for (keyword in singleSchemas) schema.optJSONObject(keyword)?.let { visit(it, false) }
        for (keyword in listOf("items", "prefixItems")) {
            when (val children = schema.opt(keyword)) {
                is JSONObject -> visit(children, false)
                is JSONArray -> for (index in 0 until children.length()) {
                    children.optJSONObject(index)?.let { visit(it, false) }
                }
            }
        }
    }
}

internal object VertexGenerateContentProvider : AgentProviderClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override val id: String = "vertex_generate_content"
    override val capabilities = ProviderCapabilities(
        endpoint = EndpointKind.VERTEX_GENERATE_CONTENT,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = true,
        toolResultImages = true,
        strictTools = false,
        parallelToolCalls = true,
    )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val config = request.effectiveConfig
        val headers = Headers.Builder()
            .apply {
                ProviderRequestHeaders.mergeInto(this, config.baseUrl, config.customHeaders, request.sessionId)
                if (config.apiKey.isNotBlank()) set("x-goog-api-key", config.apiKey)
                set("Content-Type", "application/json; charset=utf-8")
                set("Accept", "text/event-stream")
            }.build()
        val url = ProviderUrls.vertexStreamGenerateContentUrl(
            baseUrl = config.baseUrl,
            model = config.model,
            project = config.vertexProject,
            location = config.vertexLocation,
        )
        val payload = VertexGenerateContentRequest.build(
            config, request.messages, request.effectiveTools, request.purpose, request.toolResultImages,
        ).toString()
        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        val client = AgentHttpClient.vertexClient(AgentHttpClient.modelClient(request.purpose))
        val call = client.newCall(httpRequest)
        val binding = runController.register(interruptible = true, cancel = call::cancel)
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                runController.throwIfCancelled()
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                if (!response.isSuccessful) {
                    throw AgentModelFailure.http(response.code, response.peekBody(16_384).string(), headers = response.headers)
                }
                val assistant = VertexGenerateContentStream(onEvent).read(response.body.byteStream(), runController)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason")))
                return ProviderResponse(assistant)
            }
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            throw failure
        } finally {
            binding.close()
        }
    }
}
