package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/** Requested service tier, not a guarantee of provider support or latency. */
@Serializable
enum class GptSpeedMode {
    NORMAL,
    FAST,
    ULTRA_FAST;

    fun next(): GptSpeedMode = when (this) {
        NORMAL -> FAST
        FAST -> ULTRA_FAST
        ULTRA_FAST -> NORMAL
    }
}

// Match the model ID, optionally qualified by provider namespaces, not a display-name substring.
// No version allowlist: support for a requested tier is determined by the provider's response.
private val GPT_SPEED_MODEL_ID = Regex(
    "^(?:[a-z0-9][a-z0-9._-]*/)*gpt-[0-9][a-z0-9._:-]*$",
    RegexOption.IGNORE_CASE,
)
private val NON_TEXT_MODEL_VARIANT = Regex(
    "(?:^|[-._:])(image|audio|realtime|tts|transcribe|transcription)(?:$|[-._:])",
    RegexOption.IGNORE_CASE,
)

fun isGptSpeedModel(modelId: String): Boolean {
    val id = modelId.trim()
    return GPT_SPEED_MODEL_ID.matches(id) &&
        !NON_TEXT_MODEL_VARIANT.containsMatchIn(id.substringAfterLast('/'))
}

/** Shared eligibility for UI binding and run snapshots; unknown protocols fail closed. */
internal fun supportsGptSpeedProtocol(providerType: String, endpointMode: String): Boolean =
    providerType == ProviderTypes.OPENAI_COMPATIBLE &&
        (endpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS || endpointMode == OpenAiEndpointMode.RESPONSES)

internal fun supportsGptSpeedBinding(provider: ProviderSetting?, model: Model?): Boolean =
    provider is OpenAiCompatibleProviderSetting && provider.isEnabled && model != null && model.isEnabled &&
        supportsGptSpeedProtocol(ProviderTypes.OPENAI_COMPATIBLE, provider.endpointMode) &&
        isGptSpeedModel(model.modelId) && !model.supportsImageGeneration &&
        !model.supportsVideoGeneration && !model.supportsSpeechSynthesis &&
        (model.outputModalities.isEmpty() || model.outputModalities.any { it.equals(Model.TEXT_MODALITY, ignoreCase = true) })
