package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.oauth.GoogleAntigravityOAuth
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.ProviderRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object AntigravityProvider : AgentProviderClient {
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    override val id: String = "google_antigravity"
    override val capabilities = ProviderCapabilities(
        endpoint = EndpointKind.CHAT_COMPLETIONS,
        streamingText = true,
        streamingToolCalls = true,
        imageInput = true,
        toolResultImages = false,
        strictTools = false,
        parallelToolCalls = true,
    )

    override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
        val config = request.config
        val projectId = GoogleAntigravityOAuth.ensureProjectId(
            ProviderRepository.context(),
            config.providerId,
            config.apiKey,
        ).orEmpty()
        val body = AntigravityRequestBuilder.build(config, request.messages, request.tools, projectId, request.sessionId)
            .toString().toRequestBody(JSON_MEDIA_TYPE)
        val url = GoogleAntigravityOAuth.BASE_URL.trimEnd('/') + "/v1internal:streamGenerateContent?alt=sse"
        fun buildRequest(token: String): Request {
            val resolvedHeaders = okhttp3.Headers.Builder()
                .add("Content-Type", "application/json")
                .add("Accept", "text/event-stream")
                .apply { if (token.isNotBlank()) add("Authorization", "Bearer " + token) }
                .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders, request.sessionId) }
                .build()
            val requestUrl = if (GoogleAntigravityOAuth.isAntigravityEndpoint(config.baseUrl)) {
                config.baseUrl.trimEnd('/') + "/v1internal:streamGenerateContent?alt=sse"
            } else url
            return Request.Builder().url(requestUrl).headers(resolvedHeaders).post(body).build()
        }
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            return try {
                val assistant = readStreaming(buildRequest(config.apiKey), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                ProviderResponse(assistant)
            } catch (failure: AgentModelFailure) {
                if (failure.code != "HTTP_401") throw failure
                val refreshed = kotlinx.coroutines.runBlocking {
                    GoogleAntigravityOAuth.validAccessToken(
                        ProviderRepository.context(),
                        config.providerId,
                        forceRefresh = true,
                    )
                } ?: throw AgentModelFailure(
                    code = "HTTP_401",
                    retryable = false,
                    message = "反重力登录已过期，请到提供商页重新登录 Google。",
                )
                val assistant = readStreaming(buildRequest(refreshed), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                ProviderResponse(assistant)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }.getOrElse { throw it }
            openGoogleValidation(throwable)
            throw throwable
        }
    }

    private fun readStreaming(request: Request, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): JSONObject {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = linkedMapOf<String, StreamingTool>()
        var nextIndex = 0
        var active: Pair<AssistantBlockKind, Int>? = null
        var finish: String? = null
        // Gemini 3.x 的 thoughtSignature 随响应"第一个 part"返回：一轮里常是
        // [思考 part][functionCall part]，签名挂在思考 part 上，只从 functionCall
        // 那个 part 读会读空。这里整轮跟踪，最后兜底回填。
        var turnSignature = ""
        var saw = false
        fun finishBlock() {
            val block = active ?: return
            onEvent(ProviderEvent.BlockEnd(kind = block.first, index = block.second))
            active = null
        }
        fun append(kind: AssistantBlockKind, delta: String) {
            if (delta.isEmpty()) return
            if (active?.first != kind) {
                finishBlock()
                val index = nextIndex++
                active = kind to index
                onEvent(ProviderEvent.BlockStart(kind, index))
            }
            onEvent(ProviderEvent.BlockDelta(kind, active!!.second, delta))
        }
        AgentSseClient.collect(request, runController, client = AgentHttpClient.antigravityClient, onOpen = { onEvent(ProviderEvent.ResponseHeaders(it)) }, onEvent = sse@{ _, _, data ->
            val payload = data.trim()
            if (payload.isBlank() || payload == "[DONE]") return@sse
            saw = true
            val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: return@sse
            val response = chunk.optJSONObject("response") ?: chunk
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0) ?: return@sse
            val reason = candidate.optString("finishReason")
            if (reason.isNotBlank() && reason != "null") {
                finish = if (reason.equals("STOP", true)) "stop" else if (reason.contains("MAX", true)) "length" else reason.lowercase()
            }
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return@sse
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val partSignature = part.optString("thoughtSignature")
                    .ifBlank { part.optString("thought_signature") }
                if (partSignature.isNotBlank()) turnSignature = partSignature
                val thought = part.optBoolean("thought") || part.has("thought") && part.opt("thought") == true
                if (part.has("text")) {
                    val text = part.optString("text")
                    if (thought) { reasoning.append(text); append(AssistantBlockKind.THINKING, text) }
                    else { content.append(text); append(AssistantBlockKind.TEXT, text) }
                }
                val call = part.optJSONObject("functionCall") ?: continue
                // 诊断：只记键名与签名长度，不记内容。若 CCA 把签名放在别的字段名上，
                // 这一行能直接看出来，省掉一轮猜测。
                runCatching {
                    io.github.mangi.eta.core.AndroidAgentLogger.info(
                        "Antigravity functionCall keys=" + part.keys().asSequence().joinToString(",") +
                            " turnSignatureChars=" + turnSignature.length,
                    )
                }
                finishBlock()
                val name = call.optString("name")
                val args = call.opt("args")
                val argsText = when (args) {
                    is JSONObject -> args.toString()
                    else -> args?.toString() ?: "{}"
                }
                val id = "call_" + UUID.randomUUID().toString().take(8)
                val index = nextIndex++
                // Gemini 3.x 要求 functionCall 原样带回 thoughtSignature，否则续轮 400
                // （Function call is missing a thought_signature in functionCall parts）。
                toolCalls[id] = StreamingTool(
                    id, name, argsText, index,
                    signature = partSignature.ifBlank { turnSignature },
                )
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, index))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, index, argsText))
            }
        })
        if (finish.isNullOrBlank() && (runController.hasPendingSteering || runController.hasPausedInterrupt)) {
            finishBlock()
            return interruptedAssistantMessage(content.toString(), reasoning.toString())
        }
        if (!saw) throw AgentModelFailure.incompleteStream("模型接口未返回 SSE data chunk")
        finishBlock()
        toolCalls.values.forEach { call ->
            onEvent(ProviderEvent.BlockEnd(kind = AssistantBlockKind.TOOL_CALL, index = call.index, blockId = call.id, name = call.name, content = call.args))
        }
        // 带工具调用就必须报 tool_calls：CCA 在 END_TURN 等终止态下仍可能带回
        // functionCall，若原样报成 end_turn/stop，运行时会判 UNEXPECTED_TOOL_CALL
        // 并丢弃整批调用（与 AnthropicMessagesProvider 的取值口径一致）。
        finish = if (toolCalls.isNotEmpty()) "tool_calls" else finish ?: "stop"
        return JSONObject().put("role", "assistant").put("content", content.toString())
            .put("reasoning_content", reasoning.toString()).put("finish_reason", finish)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put("tool_calls", JSONArray().also { array ->
                        toolCalls.values.forEach { call ->
                            array.put(
                                JSONObject().put("id", call.id).put("type", "function")
                                    .put("function", JSONObject().put("name", call.name).put("arguments", call.args))
                                    .also { entry ->
                                        val signature = call.signature.ifBlank { turnSignature }
                                        if (signature.isNotBlank()) {
                                            entry.put("thought_signature", signature)
                                        }
                                    },
                            )
                        }
                    })
                }
            }
    }

    private data class StreamingTool(
        val id: String,
        val name: String,
        val args: String,
        val index: Int,
        val signature: String = "",
    )

    private fun openGoogleValidation(error: Throwable) {
        val body = error.message.orEmpty()
        val url = AgentModelFailure.extractGoogleValidationUrl(null, body) ?: return
        runCatching {
            ProviderRepository.context().startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
