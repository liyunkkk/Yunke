package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.ProviderUrls
import java.util.Base64
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object SpeechProtocols {
    fun request(engine: SpeechEngine, config: AgentModelClient.ModelConfig, text: String, voice: String): Request =
        when (engine) {
            SpeechEngine.OPENAI, SpeechEngine.GROQ -> openAi(config, text, voice, wav = engine == SpeechEngine.GROQ)
            SpeechEngine.STEP -> step(config, text, voice)
            SpeechEngine.MIMO -> mimo(config, text, voice)
            SpeechEngine.MINIMAX -> minimax(config, text, voice)
            SpeechEngine.QWEN -> qwen(config, text, voice)
            SpeechEngine.XAI -> xai(config, text, voice)
            SpeechEngine.GEMINI -> gemini(config, text, voice)
            SpeechEngine.ELEVENLABS -> elevenLabs(config, text, voice)
            SpeechEngine.FISH -> fish(config, text, voice)
            SpeechEngine.DOUBAO -> error("doubao uses dedicated client")
        }

    fun decode(engine: SpeechEngine, contentType: String?, bytes: ByteArray): ByteArray = when (engine) {
        SpeechEngine.MIMO -> DoubaoSpeech.pcmToWav(decodeMimoSse(bytes.decodeToString()))
        SpeechEngine.MINIMAX -> decodeMiniMaxSse(bytes.decodeToString())
        SpeechEngine.QWEN -> decodeQwenSse(bytes.decodeToString())
        SpeechEngine.GEMINI -> DoubaoSpeech.pcmToWav(decodeGemini(bytes.decodeToString()))
        SpeechEngine.GROQ -> bytes
        else -> if (DoubaoSpeech.decodeAudio(bytes).extension == "wav") {
            DoubaoSpeech.decodeAudio(bytes).bytes
        } else {
            bytes
        }
    }

    internal fun decodeMimoSse(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        sseData(text).forEach { payload ->
            if (payload == "[DONE]") return@forEach
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
            val encoded = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optJSONObject("audio")
                ?.optString("data")
                .orEmpty()
            if (encoded.isNotBlank()) out.write(Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() }))
        }
        speechCheck(out.size() > 0) { "小米语音没有返回音频" }
        return out.toByteArray()
    }

    internal fun decodeMiniMaxSse(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        sseData(text).forEach { payload ->
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
            val hex = json.optJSONObject("data")?.optString("audio").orEmpty().replace("\\s".toRegex(), "")
            if (hex.length >= 2 && hex.length % 2 == 0) {
                val bytes = ByteArray(hex.length / 2)
                var i = 0
                while (i < hex.length) {
                    bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
                    i += 2
                }
                out.write(bytes)
            }
        }
        speechCheck(out.size() > 0) { "MiniMax 没有返回音频" }
        return out.toByteArray()
    }

    internal fun decodeQwenSse(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        sseData(text).forEach { payload ->
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
            val encoded = json.optJSONObject("output")
                ?.optJSONObject("audio")
                ?.optString("data")
                .orEmpty()
            if (encoded.isNotBlank()) out.write(Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() }))
        }
        speechCheck(out.size() > 0) { "通义语音没有返回音频" }
        return out.toByteArray()
    }

    internal fun decodeGemini(text: String): ByteArray {
        val json = JSONObject(text)
        val encoded = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optJSONObject("inlineData")
            ?.optString("data")
            .orEmpty()
        speechCheck(encoded.isNotBlank()) { "Gemini 没有返回音频" }
        return Base64.getDecoder().decode(encoded.filterNot { it.isWhitespace() })
    }

    internal fun sseData(text: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("data:") -> {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(line.removePrefix("data:").trim())
                }
                line.isEmpty() && buf.isNotEmpty() -> {
                    out += buf.toString()
                    buf.setLength(0)
                }
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    private fun openAi(config: AgentModelClient.ModelConfig, text: String, voice: String, wav: Boolean): Request {
        val format = if (wav) "wav" else "mp3"
        val headers = bearer(config).newBuilder().add("Accept", if (wav) "audio/wav" else "audio/mpeg").build()
        val payload = JSONObject().put("model", config.model).put("input", text)
            .put("voice", voice).put("response_format", format).toString()
        return Request.Builder().url(ProviderUrls.openAiAudioSpeechUrl(config.baseUrl))
            .headers(headers).post(jsonBody(payload)).build()
    }

    private fun step(config: AgentModelClient.ModelConfig, text: String, voice: String): Request {
        val payload = JSONObject()
            .put("model", config.model)
            .put("input", text)
            .put("voice", voice)
            .put("responseFormat", "mp3")
            .put("sampleRate", 24000)
            .toString()
        val url = stepSpeechUrl(config.baseUrl)
        return Request.Builder().url(url).headers(bearer(config).newBuilder().add("Accept", "application/octet-stream").build())
            .post(jsonBody(payload)).build()
    }

    internal fun mimoClone(config: AgentModelClient.ModelConfig, text: String, reference: String): Request {
        speechCheck(reference.startsWith("data:audio/mpeg;base64,") || reference.startsWith("data:audio/wav;base64,")) { "请选择有效的参考录音" }
        val payload = JSONObject().put("model", "mimo-v2.5-tts-voiceclone")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", ""))
                .put(JSONObject().put("role", "assistant").put("content", text)))
            .put("audio", JSONObject().put("format", "wav").put("voice", reference))
            .put("stream", false)
        val headers = Headers.Builder().add("Content-Type", "application/json").add("Accept", "application/json")
            .add("api-key", config.apiKey).add("Authorization", "Bearer ${config.apiKey}")
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }.build()
        return Request.Builder().url(ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)).headers(headers)
            .post(jsonBody(payload.toString())).build()
    }

    internal fun decodeMimoClone(bytes: ByteArray): ByteArray {
        val encoded = JSONObject(bytes.decodeToString()).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getJSONObject("audio").getString("data")
        speechCheck(encoded.length <= CloudSpeechSynthesizer.MAX_AUDIO_BYTES) { "试听音频超过大小限制" }
        val wav = Base64.getDecoder().decode(encoded)
        speechCheck(wav.size >= 44 && wav.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            wav.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") { "MiMo 未返回有效的 WAV 音频" }
        return wav
    }

    private fun mimo(config: AgentModelClient.ModelConfig, text: String, voice: String): Request {
        val payload = JSONObject()
            .put("model", config.model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "assistant").put("content", text)),
            )
            .put("audio", JSONObject().put("format", "pcm16").put("voice", voice))
            .put("stream", true)
            .toString()
        val headers = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("api-key", config.apiKey)
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }
            .build()
        return Request.Builder().url(ProviderUrls.openAiChatCompletionsUrl(config.baseUrl))
            .headers(headers).post(jsonBody(payload)).build()
    }

    private fun minimax(config: AgentModelClient.ModelConfig, text: String, voice: String): Request {
        val payload = JSONObject()
            .put("model", config.model)
            .put("text", text)
            .put("stream", true)
            .put("output_format", "hex")
            .put("voice_setting", JSONObject().put("voice_id", voice).put("speed", 1.0))
            .toString()
        val url = join(config.baseUrl, "t2a_v2")
        return Request.Builder().url(url).headers(bearer(config).newBuilder().add("Accept", "text/event-stream").build())
            .post(jsonBody(payload)).build()
    }

    private fun qwen(config: AgentModelClient.ModelConfig, text: String, voice: String): Request {
        val payload = JSONObject()
            .put("model", config.model)
            .put(
                "input",
                JSONObject().put("text", text).put("voice", voice).put("format", "wav").put("sample_rate", 24000),
            )
            .toString()
        val headers = bearer(config).newBuilder()
            .add("X-DashScope-SSE", "enable")
            .add("Accept", "text/event-stream")
            .build()
        return Request.Builder().url(qwenUrl(config.baseUrl)).headers(headers).post(jsonBody(payload)).build()
    }

    private fun xai(config: AgentModelClient.ModelConfig, text: String, voice: String): Request {
        val payload = JSONObject().put("text", text).put("voice_id", voice).put("language", "auto").toString()
        return Request.Builder().url(join(config.baseUrl, "tts")).headers(bearer(config)).post(jsonBody(payload)).build()
    }

    private fun gemini(config: AgentModelClient.ModelConfig, text: String, voice: String): Request {
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)),
                        ),
                    ),
            )
            .put("model", config.model)
            .toString()
        val url = "${config.baseUrl.trim().trimEnd('/')}/models/${config.model}:generateContent"
        val headers = Headers.Builder()
            .add("Content-Type", "application/json")
            .apply { if (config.apiKey.isNotBlank()) add("x-goog-api-key", config.apiKey) }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }
            .build()
        return Request.Builder().url(url).headers(headers).post(jsonBody(payload)).build()
    }

    private fun elevenLabs(config: AgentModelClient.ModelConfig, text: String, voice: String): Request {
        val payload = JSONObject()
            .put("text", text)
            .put("model_id", config.model.ifBlank { "eleven_multilingual_v2" })
            .toString()
        val headers = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "audio/mpeg")
            .apply { if (config.apiKey.isNotBlank()) add("xi-api-key", config.apiKey) }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }
            .build()
        return Request.Builder()
            .url("${config.baseUrl.trim().trimEnd('/')}/v1/text-to-speech/$voice?output_format=mp3_44100_128")
            .headers(headers).post(jsonBody(payload)).build()
    }

    private fun fish(config: AgentModelClient.ModelConfig, text: String, voice: String): Request {
        val payload = JSONObject().put("text", text).put("format", "mp3")
            .apply { if (voice.isNotBlank()) put("reference_id", voice) }
            .toString()
        val headers = bearer(config).newBuilder().add("model", config.model).build()
        return Request.Builder().url(join(config.baseUrl, "v1/tts")).headers(headers).post(jsonBody(payload)).build()
    }

    private fun bearer(config: AgentModelClient.ModelConfig): Headers {
        val builder = Headers.Builder().add("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) builder.add("Authorization", "Bearer ${config.apiKey}")
        ProviderRequestHeaders.mergeInto(builder, config.baseUrl, config.customHeaders)
        return builder.build()
    }

    private fun jsonBody(payload: String) = payload.toRequestBody("application/json".toMediaType())

    private fun join(baseUrl: String, path: String): String {
        val base = ProviderUrls.normalizeBaseUrl(baseUrl)
        val clean = path.trimStart('/')
        return if (base.endsWith("/$clean") || base.endsWith(clean)) base else "$base/$clean"
    }

    private fun stepSpeechUrl(baseUrl: String): String {
        val normalized = ProviderUrls.normalizeBaseUrl(baseUrl)
        return if (normalized.endsWith("/v1")) "$normalized/audio/speech" else "$normalized/v1/audio/speech"
    }

    private fun qwenUrl(baseUrl: String): String {
        val host = baseUrl.toHttpUrlOrNull()?.host.orEmpty()
        return if (host.contains("dashscope") && !baseUrl.contains("maas.aliyuncs.com")) {
            "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
        } else {
            join(baseUrl, "services/audio/tts/SpeechSynthesizer")
        }
    }
}
