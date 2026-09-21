package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SpeechEngine {
    OPENAI,
    DOUBAO,
    MIMO,
    MINIMAX,
    STEP,
    QWEN,
    GROQ,
    XAI,
    GEMINI,
    ELEVENLABS,
    FISH,
    COSYVOICE,
    MOSS,
}

internal object SpeechEngineResolver {
    fun resolve(provider: ProviderSetting?, model: String): SpeechEngine =
        resolve(
            sourceType = provider?.let { ProviderSourceRegistry.resolve(it) }.orEmpty(),
            baseUrl = provider?.baseUrl.orEmpty(),
            model = model,
        )

    fun resolve(sourceType: String, baseUrl: String, model: String): SpeechEngine {
        val host = baseUrl.trim().toHttpUrlOrNull()?.host.orEmpty().lowercase()
        val id = model.lowercase()
        val source = sourceType.trim().lowercase()
        return when {
            DoubaoSpeech.isOpenspeech(baseUrl) || source == ProviderSourceTypes.DOUBAO_SPEECH ||
                (DoubaoSpeech.matchesModel(model) && !host.contains("volces.com") && !host.contains("volcengine.com")) ->
                SpeechEngine.DOUBAO
            host.contains("xiaomimimo") || source == ProviderSourceTypes.MIMO || id.startsWith("mimo") ->
                SpeechEngine.MIMO
            host.contains("minimax") || source == ProviderSourceTypes.MINIMAX ||
                id.startsWith("speech-2") || id.startsWith("speech-01") -> SpeechEngine.MINIMAX
            host.contains("stepfun") || source == ProviderSourceTypes.STEPFUN ||
                id.startsWith("step-tts") || id.startsWith("stepaudio") -> SpeechEngine.STEP
            host.contains("maas.aliyuncs.com") ||
                (host.contains("dashscope") && !baseUrl.contains("compatible-mode", ignoreCase = true)) ||
                id.startsWith("qwen-audio") || id.startsWith("qwen3-tts") -> SpeechEngine.QWEN
            host.contains("groq.com") || id.contains("orpheus") || id.contains("playai") -> SpeechEngine.GROQ
            host == "api.x.ai" || host.endsWith(".x.ai") -> SpeechEngine.XAI
            host.contains("generativelanguage.googleapis.com") ||
                (id.contains("gemini") && id.contains("tts")) -> SpeechEngine.GEMINI
            host.contains("elevenlabs") -> SpeechEngine.ELEVENLABS
            host.contains("fish.audio") || id.contains("fish-speech") -> SpeechEngine.FISH
            "cosyvoice" in id -> SpeechEngine.COSYVOICE
            ("moss-ttsd" in id || "moss_ttsd" in id) ->
                SpeechEngine.MOSS
            else -> SpeechEngine.OPENAI
        }
    }
}
