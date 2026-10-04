package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentPresetCatalog
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Archive boundary for owner-scoped child-agent settings. Never consults the active UI owner. */
internal object BackupSubAgentConfig {
    private const val SEED = "agent_conversation_child_seed_v1"
    private const val PREFIX = "agent_conversation_child_owner_v1_"

    /** Full backup values use Prefs' type prefix. Validate BEFORE clearing local preferences. */
    fun containsPreferences(values: Map<String, String>): Boolean = values.keys.any(::isSubAgentPreference)

    private fun isSubAgentPreference(key: String): Boolean = key == SEED || key == SubAgentPresetCatalog.KEY ||
        key.startsWith(PREFIX) || key.startsWith(ConversationSubAgentPreferences.BIND_PREFIX)

    fun validatePreferences(values: Map<String, String>, store: ConversationSubAgentPreferences) {
        val payloads = values.filterKeys(::isSubAgentPreference).mapValues { (key, encoded) ->
            require(encoded.startsWith("s:")) { "子代理配置类型无效：$key" }
            encoded.substring(2)
        }
        store.validatePreferenceArchives(payloads)
    }

    /** An old conversation archive is independent of both current selection and live seed. */
    fun archiveForImport(schemaVersion: Int, archive: String?, store: ConversationSubAgentPreferences): String {
        require(schemaVersion in 1..EtaConversationExport.SCHEMA_VERSION) { "不支持的会话备份版本" }
        require(schemaVersion >= 3 || archive == null) { "旧会话备份不能包含新版本子代理配置" }
        val resolved = archive ?: legacyArchive()
        store.validateArchive(resolved)
        return resolved
    }

    fun archiveForExport(conversationId: String, store: ConversationSubAgentPreferences): String =
        store.export(SubAgentConfigKey.Conversation(conversationId))

    /** Detect even malformed existing values, without decoding or overwriting them. */
    fun hasOwner(preferences: SharedPreferences, conversationId: String): Boolean =
        preferences.contains(ownerKey(conversationId))

    fun removeImportedOwner(preferences: SharedPreferences, conversationId: String) {
        check(preferences.edit().remove(ownerKey(conversationId)).commit()) { "会话子代理配置回滚失败" }
    }

    private fun ownerKey(id: String): String = PREFIX + "c_" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))

    private fun legacyArchive(): String {
        val profiles = listOf(0, 2, 3, 1).map { slot ->
            SubAgentProfile(
                id = "legacy-$slot",
                name = when (slot) {
                    0 -> "执行代理 1"
                    1 -> "审查／总结代理"
                    2 -> "执行代理 2"
                    else -> "执行代理 3"
                },
                role = if (slot == 1) "review" else "implementation",
            ).toJson()
        }
        return JSONObject().put("version", 1).put("enabled", true)
            .put("diagnostics_enabled", false).put("agents", JSONArray(profiles))
            .put("parallel_limits", JSONArray()).toString()
    }
}
