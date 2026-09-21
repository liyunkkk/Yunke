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
        mask: InputImage? = null,
    ): Result {
        runController?.throwIfCancelled()
        require(config.baseUrl.isNotBlank()) { "请先配置 API 地址" }
        require(prompt.isNotBlank()) { "请输入图片描述" }
        val parsedPrompt = ImagePromptOptions.parse(prompt)
        require(parsedPrompt.prompt.isNotBlank()) { "请输入图片描述" }
        val prepared = ImageRequestParameters.prepare(
            generationsBody(config, parsedPrompt.prompt), parsedPrompt.options, options,
        )
        if (config.providerType == ProviderTypes.ANTHROPIC && !prepared.explicitEndpoint)
            AgentImageGenerationOptions.invalid("Anthropic 原生消息协议不是生图接口；若该中转另有 Images 端点，请显式配置 eta_image_config.endpoint。")
        val plan = ImageEndpointPlan.create(prepared, images.size, mask != null)
        (images + listOfNotNull(mask)).forEach {
            require(it.bytes.isNotEmpty() && it.bytes.size <= MAX_AGENT_IMAGE_BYTES && it.bytes.hasSupportedImageMagic()) {
                "参考图或遮罩为空、过大或不是支持的图片；不会丢弃后继续生成。"
            }
        }
        if (mask != null) {
            require(mask.bytes.sniffAgentImageMimeType() == "image/png") { "遮罩必须为 PNG。" }
            fun bounds(bytes: ByteArray): Pair<Int, Int> {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                return b.outWidth to b.outHeight
            }
            val size = bounds(images.single().bytes)
            require(size.first > 0 && size.second > 0 && bounds(mask.bytes) == size) { "遮罩尺寸必须与参考图一致；不会缩放。" }
        }
        val request = buildRequest(config, plan, images, mask)
        // One selected endpoint, one billable POST. No chat fallback or parameter-changing retries.
        val parsed = executeGenerationRequest(generationHttpClient, request, runController) { response ->
            val limit = if (plan.kind == ImageEndpointPlan.Kind.NOVELAI) NovelAiImageProtocol.MAX_RESPONSE_BYTES
                else MAX_AGENT_IMAGE_BYTES / 3 * 4 + 1024 * 1024
            val bytes = response.body.byteStream().readGenerationBytes(limit)
            check(response.isSuccessful) { AgentImageGenerationParser.errorMessage(bytes.toString(Charsets.UTF_8), response.code) }
            if (plan.kind == ImageEndpointPlan.Kind.NOVELAI)
                NovelAiImageProtocol.parse(bytes) { runController?.throwIfCancelled() }
            else AgentImageGenerationParser.parse(bytes.toString(Charsets.UTF_8))
        }
        val generated = materialize(parsed)
        check(generated.images.isNotEmpty()) { "响应里没有图片；不会自动重发可能已计费的请求。" }
        val reports = generated.images.mapIndexed { index, image ->
            "图片 ${index + 1}：" + plan.options.dimensionReport(image.width, image.height)
        }.toMutableList()
        val expectedCount = plan.options.count ?: 1
        if (generated.images.size != expectedCount)
            reports += "IMAGE_COUNT_MISMATCH：请求 $expectedCount 张，实际 ${generated.images.size} 张。"
        val summary = if (plan.kind == ImageEndpointPlan.Kind.NOVELAI)
            "端点协议：novelai_native；发送尺寸：${plan.options.size}；n_samples：$expectedCount"
        else "端点协议：${plan.kind.name.lowercase()}；${prepared.summary}"
        return generated.copy(text = summary + "\n" + reports.joinToString("\n") +
            generated.text.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty())
    }

    private fun buildRequest(
        config: AgentModelClient.ModelConfig,
        plan: ImageEndpointPlan.Plan,
        images: List<InputImage>,
        mask: InputImage?,
    ): Request {
        fun dataUrl(image: InputImage): String = "data:${image.bytes.sniffAgentImageMimeType()};base64," +
            java.util.Base64.getEncoder().encodeToString(image.bytes)
        val parameters = JSONObject(plan.body.toString())
        val body = when (plan.kind) {
            ImageEndpointPlan.Kind.EDITS_MULTIPART -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                parameters.keys().forEach { key -> addFormDataPart(key, parameters.get(key).toString()) }
                images.forEachIndexed { index, image ->
                    val mime = image.bytes.sniffAgentImageMimeType()
                    addFormDataPart("image", "image$index.${AgentImageGenerationParser.extensionForMime(mime)}", image.bytes.toRequestBody(mime.toMediaType()))
                }
                mask?.let { addFormDataPart("mask", "mask.png", it.bytes.toRequestBody("image/png".toMediaType())) }
            }.build()
            ImageEndpointPlan.Kind.EDITS_JSON -> parameters.put("image", JSONObject().put("type", "image_url")
                .put("url", dataUrl(images.single()))).toString().toRequestBody(JSON_MEDIA_TYPE)
            ImageEndpointPlan.Kind.GENERATIONS_IMAGE -> {
                parameters.put("image", dataUrl(images.single()))
                mask?.let { parameters.put("mask", dataUrl(it)) }
                parameters.toString().toRequestBody(JSON_MEDIA_TYPE)
            }
            else -> parameters.toString().toRequestBody(JSON_MEDIA_TYPE)
        }
        return Request.Builder().url(plan.url(config.baseUrl)).headers(requestHeaders(config))
            .header("Accept", if (plan.kind == ImageEndpointPlan.Kind.NOVELAI) "application/zip, image/*" else "application/json")
            .post(body).build()
    }

    private fun generationsBody(config: AgentModelClient.ModelConfig, prompt: String): JSONObject =
        JSONObject().put("model", config.model).put("prompt", prompt).put("n", 1)
            .also { mergeRequestExtras(it, config, keepMessages = false) }

    private fun mergeRequestExtras(
        target: JSONObject,
        config: AgentModelClient.ModelConfig,
        keepMessages: Boolean,
    ) {
        val messages = if (keepMessages) target.optJSONArray("messages") else null
        val prompt = if (!keepMessages) target.opt("prompt") else null
        if (config.extraBodyJson.isNotBlank()) {
            val extra = try { JSONObject(config.extraBodyJson) } catch (_: Exception) {
                AgentImageGenerationOptions.invalid("生图额外请求体不是有效 JSON 对象；不会忽略后继续发送。")
            }
            extra.keys().forEach { key -> target.put(key, extra.get(key)) }
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
