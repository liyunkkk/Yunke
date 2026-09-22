package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class AgentVideoGenerationClient(
    private val httpClient: OkHttpClient = AgentHttpClient.modelClient,
    private val runController: AgentRunController? = null,
) {
    private val generationHttpClient = httpClient.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    data class InputImage(
        val bytes: ByteArray,
        val mimeType: String,
    )

    data class GeneratedVideo(
        val bytes: ByteArray,
        val mimeType: String,
    )

    data class Result(
        val videos: List<GeneratedVideo>,
        val text: String = "",
    )

    suspend fun generate(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage> = emptyList(),
        transport: String? = null,
    ): Result {
        runController?.throwIfCancelled()
        require(config.baseUrl.isNotBlank()) { "请先配置 API 地址" }
        require(prompt.isNotBlank()) { "请输入视频描述" }
        require(config.providerType != ProviderTypes.ANTHROPIC) {
            "当前供应商不支持生视频接口"
        }
        val headers = requestHeaders(config)
        val inputImages = images.filter { it.bytes.isNotEmpty() }
        val attempts = if (transport != null) listOf(when (transport) {
            "videos_json" -> Attempt.VideosJson
            "videos_multipart" -> Attempt.VideosMultipart
            "videos_generations" -> Attempt.VideosGenerations
            "video_generations" -> Attempt.VideoGenerations
            "ark_contents" -> Attempt.ArkContents
            else -> error("未识别的媒体思考传输协议")
        }) else buildList {
            if (ArkContentsGenerations.matches(config.baseUrl)) add(Attempt.ArkContents)
            add(Attempt.VideosMultipart)
            add(Attempt.VideosJson)
            add(Attempt.VideosGenerations)
            add(Attempt.VideoGenerations)
            add(Attempt.ChatCompletions)
        }
        var lastError: String? = null
        attempts.forEach { attempt ->
            runController?.throwIfCancelled()
            currentCoroutineContext().ensureActive()
            val response = runCatching { execute(config, prompt, inputImages, headers, attempt) }
                .getOrElse { throwable ->
                    runController?.throwIfCancelled()
                    currentCoroutineContext().ensureActive()
                    lastError = throwable.message ?: throwable.javaClass.simpleName
                    return@forEach
                }
            if (response.ok) {
                val generated = resolve(config, headers, response)
                if (generated.videos.isNotEmpty()) return generated
                lastError = generated.text.takeIf { it.isNotBlank() } ?: "响应里没有视频"
                return@forEach
            }
            lastError = AgentVideoGenerationParser.errorMessage(response.body, response.code)
            if (!response.retryable) {
                error(lastError ?: "生视频失败")
            }
        }
        error(lastError ?: "生视频失败")
    }

    private enum class Attempt {
        ArkContents,
        VideosMultipart,
        VideosJson,
        VideosGenerations,
        VideoGenerations,
        ChatCompletions,
    }

    private data class RawResponse(
        val code: Int,
        val body: String,
        val bytes: ByteArray? = null,
        val contentType: String? = null,
        val ok: Boolean,
        val retryable: Boolean,
    )

    private suspend fun resolve(
        config: AgentModelClient.ModelConfig,
        headers: Headers,
        response: RawResponse,
    ): Result {
        sniffVideo(response.bytes, response.contentType)?.let { video ->
            return Result(videos = listOf(video))
        }
        val parsed = AgentVideoGenerationParser.parse(response.body)
        if (parsed.failed) {
            error(parsed.error ?: parsed.text.ifBlank { "生视频失败" })
        }
        val materialized = materialize(parsed)
        if (materialized.videos.isNotEmpty()) return materialized
        val taskId = parsed.taskId?.trim().orEmpty()
        if (taskId.isBlank()) return materialized
        if (AgentVideoGenerationParser.isTerminalSuccess(parsed.status)) {
            return pollContent(config, headers, taskId, parsed.text)
        }
        return pollTask(config, headers, taskId, parsed.text)
    }

    private suspend fun pollTask(
        config: AgentModelClient.ModelConfig,
        headers: Headers,
        taskId: String,
        text: String,
    ): Result {
        repeat(MAX_POLLS) { index ->
            currentCoroutineContext().ensureActive()
            if (index > 0) delay(POLL_INTERVAL_MS)
            val urls = buildList {
                ArkContentsGenerations.taskUrl(config.baseUrl, taskId)?.let { add(it) }
                add(ProviderUrls.openAiVideoUrl(config.baseUrl, taskId))
                add(ProviderUrls.openAiVideoGenerationUrl(config.baseUrl, taskId))
            }
            for (url in urls) {
                val response = try { get(url, headers) } catch (failure: Exception) {
                    runController?.throwIfCancelled()
                    currentCoroutineContext().ensureActive()
                    continue
                }
                if (!response.ok) continue
                sniffVideo(response.bytes, response.contentType)?.let { video ->
                    return Result(videos = listOf(video), text = text)
                }
                val parsed = AgentVideoGenerationParser.parse(response.body)
                if (parsed.failed) {
                    error(parsed.error ?: parsed.text.ifBlank { "生视频失败" })
                }
                val materialized = materialize(parsed)
                if (materialized.videos.isNotEmpty()) {
                    return materialized.copy(text = materialized.text.ifBlank { text })
                }
                if (AgentVideoGenerationParser.isTerminalSuccess(parsed.status)) {
                    val content = pollContent(config, headers, parsed.taskId ?: taskId, text)
                    if (content.videos.isNotEmpty()) return content
                }
            }
        }
        error("视频生成超时，请稍后在会话里重试")
    }

    private fun pollContent(
        config: AgentModelClient.ModelConfig,
        headers: Headers,
        taskId: String,
        text: String,
    ): Result {
        val response = get(ProviderUrls.openAiVideoContentUrl(config.baseUrl, taskId), headers)
        sniffVideo(response.bytes, response.contentType)?.let { video ->
            return Result(videos = listOf(video), text = text)
        }
        if (response.ok) {
            val materialized = materialize(AgentVideoGenerationParser.parse(response.body))
            if (materialized.videos.isNotEmpty()) {
                return materialized.copy(text = materialized.text.ifBlank { text })
            }
        }
        return Result(videos = emptyList(), text = text)
    }

    private fun execute(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
        headers: Headers,
        attempt: Attempt,
    ): RawResponse {
        val request = when (attempt) {
            Attempt.ArkContents -> Request.Builder()
                .url(ArkContentsGenerations.tasksUrl(config.baseUrl) ?: error("当前地址不是火山方舟内容生成接口"))
                .headers(headers)
                .post(arkBody(config, prompt, images).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            Attempt.VideosMultipart -> Request.Builder()
                .url(ProviderUrls.openAiVideosUrl(config.baseUrl))
                .headers(headers)
                .post(videosMultipart(config, prompt, images))
                .build()
            Attempt.VideosJson -> Request.Builder()
                .url(ProviderUrls.openAiVideosUrl(config.baseUrl))
                .headers(headers)
                .post(jsonBody(config, prompt, images).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            Attempt.VideosGenerations -> Request.Builder()
                .url(ProviderUrls.openAiVideosGenerationsUrl(config.baseUrl))
                .headers(headers)
                .post(jsonBody(config, prompt, images).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            Attempt.VideoGenerations -> Request.Builder()
                .url(ProviderUrls.openAiVideoGenerationsUrl(config.baseUrl))
                .headers(headers)
                .post(jsonBody(config, prompt, images).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            Attempt.ChatCompletions -> Request.Builder()
                .url(ProviderUrls.openAiChatCompletionsUrl(config.baseUrl))
                .headers(headers)
                .post(chatBody(config, prompt, images).toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
        return executeGenerationRequest(generationHttpClient, request, runController) { toRawResponse(it) }
    }

    private fun get(url: String, headers: Headers): RawResponse {
        val request = Request.Builder().url(url).headers(headers).get().build()
        return executeGenerationRequest(httpClient, request, runController) { toRawResponse(it) }
    }

    private fun toRawResponse(response: okhttp3.Response): RawResponse {
        val contentType = response.body.contentType()?.toString()
        val bytes = response.body.byteStream().readGenerationBytes(MAX_GENERATED_VIDEO_BYTES / 3 * 4 + 1024 * 1024)
        val retryable = response.code !in FATAL_HTTP_CODES
        return RawResponse(
            code = response.code,
            body = if (AgentVideoCodec.sniffMime(bytes) != null) "" else bytes.decodeToStringOrEmpty(),
            bytes = bytes,
            contentType = contentType,
            ok = response.isSuccessful,
            retryable = retryable,
        )
    }

    private fun arkBody(config: AgentModelClient.ModelConfig, prompt: String, images: List<InputImage>): JSONObject {
        val body = JSONObject(ArkContentsGenerations.createBody(config.model, prompt, images))
        val content = body.getJSONArray("content")
        mergeRequestExtras(body, config, keepMessages = false)
        body.remove("n")
        body.put("content", content)
        return body
    }

    private fun videosMultipart(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
    ): MultipartBody {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .addFormDataPart("prompt", prompt)
        val extras = JSONObject().also { mergeRequestExtras(it, config, keepMessages = false) }
        extras.keys().forEach { key ->
            if (key !in setOf("model", "prompt", "input_reference", "image", "image_url", "n"))
                builder.addFormDataPart(key, extras.get(key).toString())
        }
        images.firstOrNull()?.let { image ->
            val mime = image.mimeType.ifBlank { "image/png" }
            val filename = "image.${AgentImageGenerationParser.extensionForMime(mime)}"
            val body = image.bytes.toRequestBody(mime.toMediaType())
            builder.addFormDataPart("input_reference", filename, body)
        }
        return builder.build()
    }

    private fun jsonBody(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage>,
    ): JSONObject {
        val body = JSONObject()
            .put("model", config.model)
            .put("prompt", prompt)
            .put("n", 1)
        images.firstOrNull()?.let { image ->
            val mime = image.mimeType.ifBlank { "image/png" }
            val dataUrl = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(image.bytes)
            body.put("image", dataUrl)
            body.put("image_url", dataUrl)
        }
        return body.also { mergeRequestExtras(it, config, keepMessages = false) }
    }

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
        target.remove("eta_media_reasoning")
        target.remove("eta_image_config")
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
            .add("Accept", "*/*")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }
            .build()

    private fun materialize(parsed: AgentVideoGenerationParser.Parsed): Result {
        val videos = parsed.videos.mapNotNull { ref ->
            val bytes = when {
                ref.bytes != null -> ref.bytes
                !ref.url.isNullOrBlank() -> download(ref.url)
                else -> null
            } ?: return@mapNotNull null
            val mime = AgentVideoCodec.sniffMime(bytes)
                ?: ref.mimeType.takeIf { AgentVideoCodec.isVideoMime(it) }
                ?: return@mapNotNull null
            if (bytes.isEmpty() || bytes.size > MAX_GENERATED_VIDEO_BYTES) return@mapNotNull null
            GeneratedVideo(bytes = bytes, mimeType = mime)
        }
        return Result(videos = videos, text = parsed.text)
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        return executeGenerationRequest(downloadClient, request, runController) { response ->
            if (!response.isSuccessful) return@executeGenerationRequest null
            val declared = response.body.contentLength()
            if (declared > MAX_GENERATED_VIDEO_BYTES) return@executeGenerationRequest null
            val bytes = response.body.byteStream().readGenerationBytes(MAX_GENERATED_VIDEO_BYTES)
            bytes.takeIf { it.isNotEmpty() && it.size <= MAX_GENERATED_VIDEO_BYTES }
        }
    }

    private fun sniffVideo(bytes: ByteArray?, contentType: String?): GeneratedVideo? {
        val payload = bytes ?: return null
        if (payload.isEmpty() || payload.size > MAX_GENERATED_VIDEO_BYTES) return null
        val sniffed = AgentVideoCodec.sniffMime(payload)
        val declared = contentType?.substringBefore(';')?.trim().orEmpty()
        val mime = sniffed
            ?: declared.takeIf { AgentVideoCodec.isVideoMime(it) }
            ?: return null
        return GeneratedVideo(bytes = payload, mimeType = mime)
    }

    private fun ByteArray.decodeToStringOrEmpty(): String =
        runCatching { decodeToString() }.getOrDefault("")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val FATAL_HTTP_CODES = setOf(401, 402, 403)
        private const val MAX_POLLS = 120
        private const val POLL_INTERVAL_MS = 4_000L
        const val MAX_GENERATED_VIDEO_BYTES = 96 * 1024 * 1024
        private val downloadClient by lazy {
            AgentHttpClient.modelClient.newBuilder()
                .readTimeout(180_000, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
