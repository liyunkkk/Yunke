package io.github.mangi.eta.agent.model

import android.graphics.BitmapFactory

import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.media.hasSupportedImageMagic
import io.github.mangi.eta.agent.media.sniffAgentImageMimeType
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class AgentImageGenerationClient(
    private val httpClient: OkHttpClient = AgentHttpClient.modelClient,
    private val runController: AgentRunController? = null,
) {
    // Generation is billable: a transport failure is not proof the server did not generate an image.
    private val generationHttpClient = httpClient.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    data class InputImage(
        val bytes: ByteArray,
        val mimeType: String,
    )

    data class GeneratedImage(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int = 0,
        val height: Int = 0,
    )

    data class Result(
        val images: List<GeneratedImage>,
        val text: String = "",
    )

    fun generate(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage> = emptyList(),
        options: AgentImageGenerationOptions = AgentImageGenerationOptions(),
    ): Result {
        runController?.throwIfCancelled()
        require(config.baseUrl.isNotBlank()) { "请先配置 API 地址" }
        require(prompt.isNotBlank()) { "请输入图片描述" }
        require(config.providerType != ProviderTypes.ANTHROPIC) {
            "当前供应商不支持生图接口"
        }
        val headers = requestHeaders(config)
        val inputImages = images.filter { it.bytes.isNotEmpty() }
        // Validate and capture once, before any network request or billable fallback.
        val callOptions = AgentImageGenerationOptions.fromJson(options.toJson())
        val parameters = generationsBody(config, prompt).also { callOptions.applyTo(it, config.model) }
        // Apply model adaptation to both defaults and per-call values, not just a validation copy.
        val shape = JSONObject()
        listOf("size", "aspect_ratio", "resolution", "quality", "response_format", "n").forEach { key ->
            if (parameters.has(key)) shape.put(key, parameters.get(key))
        }
        val requested = AgentImageGenerationOptions.fromJson(shape)
        requested.applyTo(parameters, config.model)
        val constrained = !options.isEmpty || listOf("size", "aspect_ratio", "resolution", "quality", "response_format").any(parameters::has) || parameters.optInt("n", 1) != 1
        val effectiveOptions = requested.copy(
            aspectRatio = callOptions.aspectRatio ?: requested.aspectRatio,
            size = parameters.optString("size").takeIf { it.isNotBlank() })
        val grok = config.model.lowercase().substringAfterLast('/').startsWith("grok-imagine-image")
        if (grok && inputImages.size > 1) AgentImageGenerationOptions.invalid("当前 Grok 编辑适配仅支持一张参考图；不会丢弃额外参考图。")
        val attempts = buildList {
            if (inputImages.isNotEmpty()) {
                add(Attempt.Edits)
            } else {
                add(Attempt.Generations)
            }
            // Generic chat-completions has no reliable cross-provider image geometry contract.
            // Never drop explicit options or reference images to "make it work".
            if (!constrained && inputImages.isEmpty()) add(Attempt.ChatCompletions)
        }
        var lastError: String? = null
        attempts.forEach { attempt ->
            runController?.throwIfCancelled()
            // Do not replay requests after an ambiguous transport/server failure.
            val response = execute(config, prompt, inputImages, headers, attempt, parameters)
            if (response.ok) {
                val parsed = AgentImageGenerationParser.parse(response.body)
                val generated = materialize(parsed)
                if (generated.images.isNotEmpty()) {
                    val reports = generated.images.mapIndexed { index, image ->
                        "图片 ${index + 1}：" + effectiveOptions.dimensionReport(image.width, image.height)
                    }.toMutableList()
                    val expectedCount = parameters.optInt("n", 1)
                    if (generated.images.size != expectedCount) reports += "IMAGE_COUNT_MISMATCH：请求 $expectedCount 张，实际 ${generated.images.size} 张。"
                    return generated.copy(text = reports.joinToString("\n") +
                        generated.text.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty())
                }
                error("响应里没有图片；不会自动重发可能已计费的生图请求。")
            }
            lastError = AgentImageGenerationParser.errorMessage(response.body, response.code)
            if (!response.retryable) {
                error(lastError ?: "生图失败")
            }
        }
        error(lastError ?: "生图失败")
    }

    private enum class Attempt { Generations, Edits, ChatCompletions }

    private data class RawResponse(
        val code: Int,
        val body: String,
        val ok: Boolean,
        val retryable: Boolean,
    )

    private fun execute(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
        headers: Headers,
        attempt: Attempt,
        parameters: JSONObject,
    ): RawResponse {
        val request = when (attempt) {
            Attempt.Generations -> {
                val body = parameters.toString().toRequestBody(JSON_MEDIA_TYPE)
                Request.Builder()
                    .url(ProviderUrls.openAiImagesGenerationsUrl(config.baseUrl))
                    .headers(headers)
                    .post(body)
                    .build()
            }
            Attempt.Edits -> {
                val grok = config.model.lowercase().substringAfterLast('/').startsWith("grok-imagine-image")
                val body = if (grok) {
                    if (images.size != 1) AgentImageGenerationOptions.invalid("当前 Grok 编辑适配仅支持一张参考图；不会丢弃额外参考图。")
                    val image = images.single()
                    val mime = image.mimeType.ifBlank { "image/png" }
                    JSONObject(parameters.toString()).put("image", JSONObject().put("type", "image_url")
                        .put("url", "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(image.bytes)))
                        .toString().toRequestBody(JSON_MEDIA_TYPE)
                } else editsBody(config, prompt, images, parameters)
                Request.Builder()
                    .url(ProviderUrls.openAiImagesEditsUrl(config.baseUrl))
                    .headers(headers)
                    .post(body)
                    .build()
            }
            Attempt.ChatCompletions -> {
                val body = chatBody(config, prompt, images).toString().toRequestBody(JSON_MEDIA_TYPE)
                Request.Builder()
                    .url(ProviderUrls.openAiChatCompletionsUrl(config.baseUrl))
                    .headers(headers)
                    .post(body)
                    .build()
            }
        }
        return executeGenerationRequest(generationHttpClient, request, runController) { response ->
            val body = response.body.byteStream().readGenerationBytes(MAX_AGENT_IMAGE_BYTES / 3 * 4 + 1024 * 1024).toString(Charsets.UTF_8)
            val retryable = response.code in setOf(404, 405, 501)
            RawResponse(
                code = response.code,
                body = body,
                ok = response.isSuccessful,
                retryable = retryable,
            )
        }
    }

    private fun generationsBody(config: AgentModelClient.ModelConfig, prompt: String): JSONObject =
        JSONObject()
            .put("model", config.model)
            .put("prompt", prompt)
            .put("n", 1)
            .also { mergeRequestExtras(it, config, keepMessages = false) }

    private fun chatBody(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
    ): JSONObject {
        val content: Any = if (images.isEmpty()) {
            prompt
        } else {
            JSONArray().put(
                JSONObject().put("type", "text").put("text", prompt),
            ).also { array ->
                images.forEach { image ->
                    val mime = image.mimeType.ifBlank { "image/png" }
                    val dataUrl = "data:$mime;base64," +
                        java.util.Base64.getEncoder().encodeToString(image.bytes)
                    array.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", dataUrl)),
                    )
                }
            }
        }
        return JSONObject()
            .put("model", config.model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ),
            )
            .also { mergeRequestExtras(it, config, keepMessages = true) }
            .put("stream", false)
    }

    private fun editsBody(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
        parameters: JSONObject,
    ): MultipartBody {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .addFormDataPart("prompt", prompt)
        parameters.keys().forEach { key ->
            if (key !in setOf("model", "prompt", "image", "images", "messages", "stream", "tools", "tool_choice")) {
                builder.addFormDataPart(key, parameters.get(key).toString())
            }
        }
        images.forEachIndexed { index, image ->
            val mime = image.mimeType.ifBlank { "image/png" }
            val filename = "image$index.${AgentImageGenerationParser.extensionForMime(mime)}"
            builder.addFormDataPart(
                "image",
                filename,
                image.bytes.toRequestBody(mime.toMediaType()),
            )
        }
        return builder.build()
    }

    private fun mergeRequestExtras(
        target: JSONObject,
        config: AgentModelClient.ModelConfig,
        keepMessages: Boolean,
    ) {
        val messages = if (keepMessages) target.optJSONArray("messages") else null
        val prompt = if (!keepMessages) target.opt("prompt") else null
        if (config.extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(config.extraBodyJson) }.getOrNull()?.let { extra ->
                extra.keys().forEach { key -> target.put(key, extra.get(key)) }
            }
        }
        RequestBodyMerge.mergeCustomBody(target, config.customBody)
        target.remove("tools")
        target.remove("tool_choice")
        if (keepMessages) {
            target.put("stream", false)
            if (messages != null) target.put("messages", messages)
        } else {
            target.remove("stream")
            target.remove("messages")
            if (prompt != null) target.put("prompt", prompt)
            if (!target.has("n")) target.put("n", 1)
        }
        target.put("model", config.model)
    }

    private fun requestHeaders(config: AgentModelClient.ModelConfig): Headers =
        Headers.Builder()
            .add("Accept", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }
            .build()

    private fun materialize(parsed: AgentImageGenerationParser.Parsed): Result {
        val images = parsed.images.mapNotNull { ref ->
            val bytes = when {
                ref.bytes != null -> ref.bytes
                !ref.url.isNullOrBlank() -> download(ref.url)
                else -> null
            } ?: return@mapNotNull null
            if (bytes.isEmpty() || bytes.size > MAX_AGENT_IMAGE_BYTES) return@mapNotNull null
            val mime = when {
                bytes.hasSupportedImageMagic() -> bytes.sniffAgentImageMimeType()
                ref.mimeType.startsWith("image/") -> ref.mimeType
                else -> return@mapNotNull null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            GeneratedImage(bytes = bytes, mimeType = mime, width = bounds.outWidth, height = bounds.outHeight)
        }
        return Result(images = images, text = parsed.text)
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        return executeGenerationRequest(downloadClient, request, runController) { response ->
            if (!response.isSuccessful) return@executeGenerationRequest null
            val declared = response.body.contentLength()
            if (declared > MAX_AGENT_IMAGE_BYTES) return@executeGenerationRequest null
            val bytes = response.body.byteStream().readGenerationBytes(MAX_AGENT_IMAGE_BYTES)
            bytes.takeIf { it.isNotEmpty() && it.size <= MAX_AGENT_IMAGE_BYTES }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val downloadClient by lazy {
            AgentHttpClient.modelClient.newBuilder()
                .readTimeout(60_000, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
