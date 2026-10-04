package io.github.mangi.eta.agent.voice.tts

import android.content.Context
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray

/** Local selection history, scoped by provider + configured model entry. No credentials or audio. */
internal object ReadAloudVoiceHistory {
    private fun key(provider: String, model: String) = JSONArray().put(provider).put(model).toString()

    fun remember(context: Context, provider: String, model: String, voice: String, profile: SpeechPlaybackProfile = SpeechPlaybackProfile.READ_ALOUD) {
        if (provider.isBlank() || model.isBlank() || voice.isBlank()) return
        context.getSharedPreferences(profile.historyFile, Context.MODE_PRIVATE).edit()
            .putString(key(provider, model), voice).apply()
    }

    fun restore(context: Context, provider: String, model: String, profile: SpeechPlaybackProfile = SpeechPlaybackProfile.READ_ALOUD): String {
        if (provider.isBlank() || model.isBlank()) return ""
        return context.getSharedPreferences(profile.historyFile, Context.MODE_PRIVATE)
            .getString(key(provider, model), "").orEmpty()
    }

    /** Also migrates the pre-history current selection before another screen changes it. */
    fun rememberCurrent(context: Context, profile: SpeechPlaybackProfile = SpeechPlaybackProfile.READ_ALOUD) {
        remember(context,
            Prefs.getString(profile.providerKey),
            Prefs.getString(profile.modelKey),
            Prefs.getString(profile.voiceKey), profile)
    }
}
