package io.github.mangi.eta.agent.voice.doubao

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent ASR/clone credentials, in application-private preferences. Never logged. */
internal object DoubaoVoiceConfig {
    data class Config(
        val cloudAsr: Boolean = false,
        val asrKey: String = "",
        val resource: String = DoubaoAsrProtocol.resources.first(),
        val cloneKey: String = "",
        val inputEnabled: Boolean = false,
        val conversationEnabled: Boolean = true,
        val duplexEnabled: Boolean = true,
        val conversationCloudAsr: Boolean = false,
        val conversationAsrKey: String = "",
        val conversationResource: String = DoubaoAsrProtocol.resources.first(),
    )
    private val mutable = MutableStateFlow(Config())
    val state = mutable.asStateFlow()
    fun load(context: Context) {
        val p = context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE)
        // Migrate once from either previously enabled engine; explicit OFF survives reloads.
        if (!p.contains("input_enabled")) p.edit().putBoolean("input_enabled",
            p.getBoolean("asr", false) || context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE).getBoolean("enabled", false)).apply()
        mutable.value = Config(p.getBoolean("asr", false), p.getString("asr_key", "").orEmpty(),
            p.getString("resource", null)?.takeIf { it in DoubaoAsrProtocol.resources } ?: DoubaoAsrProtocol.resources.first(), p.getString("clone_key", "").orEmpty(), p.getBoolean("input_enabled", false), p.getBoolean("conversation_enabled", true), p.getBoolean("duplex_enabled", true),
            p.getBoolean("voice_conversation_asr_cloud", false),
            p.getString("voice_conversation_asr_key", "").orEmpty(),
            p.getString("voice_conversation_asr_resource", null)?.takeIf { it in DoubaoAsrProtocol.resources }
                ?: DoubaoAsrProtocol.resources.first())
    }
    fun save(context: Context, value: Config) {
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit().putBoolean("asr", value.cloudAsr).putBoolean("input_enabled", value.inputEnabled)
            .putBoolean("conversation_enabled", value.conversationEnabled).putBoolean("duplex_enabled", value.duplexEnabled)
            .putBoolean("voice_conversation_asr_cloud", value.conversationCloudAsr)
            .putString("voice_conversation_asr_key", value.conversationAsrKey.trim())
            .putString("voice_conversation_asr_resource", value.conversationResource)
            .putString("asr_key", value.asrKey.trim()).putString("resource", value.resource).putString("clone_key", value.cloneKey.trim()).apply()
        mutable.value = value.copy(asrKey = value.asrKey.trim(), cloneKey = value.cloneKey.trim(), conversationAsrKey = value.conversationAsrKey.trim())
    }
}
