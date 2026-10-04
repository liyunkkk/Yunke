package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.config.Prefs

/** Voice resources are shared, but selection and per-model history belong to each feature. */
internal enum class SpeechPlaybackProfile(
    val modeKey: String,
    val providerKey: String,
    val modelKey: String,
    val voiceKey: String,
    val historyFile: String,
) {
    READ_ALOUD(Prefs.Keys.AGENT_TTS_MODE, Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID,
        Prefs.Keys.AGENT_TTS_MODEL_ID, Prefs.Keys.AGENT_TTS_VOICE, "read_aloud_voice_history"),
    CONVERSATION(Prefs.Keys.AGENT_VOICE_CONVERSATION_MODE, Prefs.Keys.AGENT_VOICE_CONVERSATION_PROVIDER_ID,
        Prefs.Keys.AGENT_VOICE_CONVERSATION_MODEL_ID, Prefs.Keys.AGENT_VOICE_CONVERSATION_VOICE, "voice_conversation_voice_history");

    fun snapshot() = SpeechPlaybackSettings(
        cloud = Prefs.getString(modeKey) == "cloud",
        providerId = Prefs.getString(providerKey),
        modelId = Prefs.getString(modelKey),
        voiceId = Prefs.getString(voiceKey).trim(),
    )
}

internal data class SpeechPlaybackSettings(
    val cloud: Boolean,
    val providerId: String,
    val modelId: String,
    val voiceId: String,
)
