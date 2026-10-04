package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentModelDefaults
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentPresetCatalog
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Archive boundary for owner-scoped child-agent settings. Never consults the active UI owner. */
internal object BackupSubAgentConfig {
    private const val SEED = ConversationSubAgentPreferences.SEED_KEY
    private const val PREFIX = ConversationSubAgentPreferences.OWNER_PREFIX
    private const val MARKER = ConversationSubAgentPreferences.RESET_MARKER_KEY
    private const val COMPLETED_MARKER = "s:1"
    private val legacySlot = Regex("agent_child_[0-3]_(provider|model|reasoning|task_tier)")
    private val legacyParallelLimit = Regex("agent_model_parallel_[0-9a-f]{64}")

    /** Full backup values use Prefs' type prefix. Validate BEFORE clearing local preferences. */
    fun containsPreferences(values: Map<String, String>): Boolean = values.keys.any(::isSubAgentPreference)

    private fun isSubAgentPreference(key: String): Boolean = key == SEED || key == SubAgentPresetCatalog.KEY ||
        key == SubAgentModelDefaults.KEY || key == MARKER || key == ConversationSubAgentPreferences.UI_DRAFT_KEY ||
        key.startsWith(PREFIX) ||
        key.startsWith(ConversationSubAgentPreferences.BIND_PREFIX)

    /** Exact historical namespaces only; never filter provider, chat or general model settings. */
    private fun isLegacyPreference(key: String): Boolean = key == SubAgentPreferences.PROFILES_KEY ||
        legacySlot.matches(key) || legacyParallelLimit.matches(key) || key.startsWith("agent_collaboration_")

    fun validatePreferences(values: Map<String, String>, store: ConversationSubAgentPreferences) {
        val payloads = values.filterKeys(::isSubAgentPreference).mapValues { (key, encoded) ->
            require(encoded.startsWith("s:")) { "子代理配置类型无效：$key" }
            encoded.substring(2)
        }
        // The data repository owns the schema, including model defaults and the generation marker.
        store.validatePreferenceArchives(payloads)
    }

    /** Old-generation payloads will be ignored, not decoded or resurrected by an external restore. */
    fun validateExternalPreferences(values: Map<String, String>, store: ConversationSubAgentPreferences) {
        if (values.containsKey(MARKER)) {
            require(values[MARKER] == COMPLETED_MARKER) { "子代理配置重置标记无效" }
            validatePreferences(values, store)
        }
    }

    /**
     * Startup has already completed reset. Old backups replace unrelated prefs normally, but keep
     * the ENTIRE device-local new-generation subset. New backups replace it after strict validation.
     * The marker is included in the same preferences commit, never re-added after a destructive clear.
     * Missing model defaults in a marked archive are a valid empty defaults map.
     */
    fun preferencesForRestore(
        incoming: Map<String, String>,
        current: Map<String, String>,
        store: ConversationSubAgentPreferences,
    ): Map<String, String> {
        check(store.isConfigurationResetComplete() && current[MARKER] == COMPLETED_MARKER) {
            "必须先完成启动子代理配置重置，再恢复外部备份"
        }
        validateExternalPreferences(incoming, store)
        return if (incoming[MARKER] == COMPLETED_MARKER) {
            incoming.filterKeys { !isLegacyPreference(it) } + (MARKER to COMPLETED_MARKER)
        } else {
            incoming.filterKeys { !isSubAgentPreference(it) && !isLegacyPreference(it) } +
                current.filterKeys(::isSubAgentPreference)
        }
    }

    /**
     * Internal undo is NOT an external import. Restore original scalars exactly, without running
     * Prefs' migrations, preserving/recreating a marker, resetting configs or consulting live owners.
     * Decode everything before obtaining an editor; malformed undo snapshots must fail visibly.
     */
    fun restoreExactPreferences(values: Map<String, String>, preferences: SharedPreferences) {
        val decoded: Map<String, Any> = values.mapValues { (key, encoded) ->
            require(key.isNotBlank() && encoded.length >= 2 && encoded[1] == ':') { "配置回滚数据无效" }
            val payload = encoded.substring(2)
            when (encoded[0]) {
                's' -> payload
                'b' -> requireNotNull(payload.toBooleanStrictOrNull()) { "配置回滚布尔值无效" }
                'i' -> requireNotNull(payload.toIntOrNull()) { "配置回滚整数无效" }
                'l' -> requireNotNull(payload.toLongOrNull()) { "配置回滚长整数无效" }
                else -> error("配置回滚类型无效")
            }
        }
        val editor = preferences.edit().clear()
        decoded.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                else -> error("配置回滚类型无效")
            }
        }
        check(editor.commit()) { "Agent preferences 回滚未落盘" }
    }

    /** Missing generation predates reset, even in schema 3: never decode or revive its archive. */
    fun archiveForImport(
        schemaVersion: Int,
        archive: String?,
        store: ConversationSubAgentPreferences,
        generation: String? = null,
    ): String {
        require(schemaVersion in 1..EtaConversationExport.SCHEMA_VERSION) { "不支持的会话备份版本" }
        require(generation == null || generation == "1") { "不支持的会话子代理配置代际" }
        if (generation == null) return legacyArchive()
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

    private fun legacyArchive(): String = JSONObject().put("version", 1).put("enabled", false)
        .put("diagnostics_enabled", false).put("agents", JSONArray())
        .put("parallel_limits", JSONArray()).put("legacy_parallel_limits", JSONArray()).toString()
}
