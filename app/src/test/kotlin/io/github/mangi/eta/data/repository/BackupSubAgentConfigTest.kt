package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupSubAgentConfigTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString", "putBoolean", "putInt", "putLong" -> { values[args!![0] as String] = args[1]!!; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "clear" -> { values.clear(); proxy }
                "commit" -> true
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "contains" -> values.containsKey(args!![0] as String)
                "getString" -> values[args!![0] as String] ?: args[1]
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error("Unexpected preference method ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun oldArchiveDoesNotReadCurrentSelectionOrLiveSeed() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        val active = SubAgentConfigKey.Conversation("active")
        store.update(active) { it.copy(enabled = false, profiles = emptyList(), diagnosticsEnabled = true) }
        val archive = BackupSubAgentConfig.archiveForImport(2, null, store)
        val target = SubAgentConfigKey.Conversation("new")
        assertTrue(store.importOwner(target, archive))
        assertFalse(store.snapshot(target).enabled)
        assertFalse(store.snapshot(target).diagnosticsEnabled)
        assertTrue(store.snapshot(target).profiles.isEmpty())
        assertTrue(store.snapshot(active).profiles.isEmpty())
    }

    @Test fun newOwnerMappingPreservesFullConfigAndNeverOverwritesCollision() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        val source = SubAgentConfigKey.Conversation("old")
        val model = SubAgentParallelModel("provider", "api-model")
        store.update(source) { it.copy(enabled = false, diagnosticsEnabled = true,
            parallelLimits = mapOf(model to 4)) }
        val archive = BackupSubAgentConfig.archiveForExport("old", store)
        val mapped = BackupSubAgentConfig.archiveForImport(3, archive, store)
        assertFalse(BackupSubAgentConfig.hasOwner(prefs, "new"))
        assertTrue(store.importOwner(SubAgentConfigKey.Conversation("new"), mapped))
        assertEquals(store.snapshot(source), store.snapshot(SubAgentConfigKey.Conversation("new")))
        assertEquals(4, store.snapshot(SubAgentConfigKey.Conversation("new")).parallelLimits[model])
        assertTrue(BackupSubAgentConfig.hasOwner(prefs, "new"))
        assertFalse(store.importOwner(SubAgentConfigKey.Conversation("new"), mapped))
        BackupSubAgentConfig.removeImportedOwner(prefs, "new")
        assertFalse(BackupSubAgentConfig.hasOwner(prefs, "new"))
        assertTrue(BackupSubAgentConfig.hasOwner(prefs, "old"))
    }

    private fun encoded(prefs: SharedPreferences): Map<String, String> = prefs.all.mapValues { (_, value) ->
        when (value) {
            is String -> "s:$value"
            is Boolean -> "b:$value"
            is Int -> "i:$value"
            is Long -> "l:$value"
            else -> error("Unsupported test value")
        }
    }

    @Test fun oldBackupKeepsEntireCurrentGenerationAndIgnoresObsoleteConfigs() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        store.resetLegacyConfigurationOnce()
        val preset = store.addPreset("当前预设")
        store.applyPreset(SubAgentConfigKey.Conversation("current"), preset.id)
        val current = encoded(prefs)
        val obsolete = mapOf(
            ConversationSubAgentPreferences.SEED_KEY to "s:{obsolete and malformed",
            ConversationSubAgentPreferences.OWNER_PREFIX + "c_b2xk" to "s:{obsolete",
            io.github.mangi.eta.agent.delegation.SubAgentPresetCatalog.KEY to "s:{obsolete",
            "agent_child_profiles_v1" to "s:{obsolete",
            "agent_collaboration_draft" to "s:true",
            "agent_child_0_provider" to "s:obsolete-provider",
            ConversationSubAgentPreferences.UI_DRAFT_KEY to "s:obsolete-draft",
            "provider-token" to "s:restored-token",
            "chat-setting" to "b:true",
        )
        val restored = BackupSubAgentConfig.preferencesForRestore(obsolete, current, store)
        current.forEach { (key, value) -> assertEquals(value, restored[key]) }
        assertEquals("s:restored-token", restored["provider-token"])
        assertEquals("b:true", restored["chat-setting"])
        assertFalse(restored.containsKey("agent_child_profiles_v1"))
        assertFalse(restored.containsKey("agent_collaboration_draft"))
        assertFalse(restored.containsKey("agent_child_0_provider"))
        assertFalse(restored.containsKey(ConversationSubAgentPreferences.OWNER_PREFIX + "c_b2xk"))
        assertEquals("s:1", restored[ConversationSubAgentPreferences.RESET_MARKER_KEY])
    }

    @Test fun markedBackupWithoutDefaultsIsValidAndNotOverlaidWithLiveOwners() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        store.resetLegacyConfigurationOnce()
        val owner = SubAgentConfigKey.Conversation("live")
        store.update(owner) { it.copy(diagnosticsEnabled = true) }
        val incoming = mapOf(ConversationSubAgentPreferences.RESET_MARKER_KEY to "s:1", "other" to "i:2")
        assertEquals(incoming, BackupSubAgentConfig.preferencesForRestore(incoming, encoded(prefs), store))
    }

    @Test fun malformedMarkerAndModelDefaultsRejectBeforeReplacement() {
        val store = ConversationSubAgentPreferences(preferences())
        assertTrue(runCatching {
            BackupSubAgentConfig.validateExternalPreferences(mapOf(
                ConversationSubAgentPreferences.RESET_MARKER_KEY to "b:true"), store)
        }.isFailure)
        assertTrue(runCatching {
            BackupSubAgentConfig.validateExternalPreferences(mapOf(
                ConversationSubAgentPreferences.RESET_MARKER_KEY to "s:1",
                io.github.mangi.eta.agent.delegation.SubAgentModelDefaults.KEY to "s:{invalid"), store)
        }.isFailure)
    }

    @Test fun exactUndoRestoresScalarTypesAndNeverAddsGenerationMarker() {
        val prefs = preferences()
        prefs.edit().putString(ConversationSubAgentPreferences.RESET_MARKER_KEY, "1").commit()
        val originals = mapOf("string" to "s:old", "boolean" to "b:false", "int" to "i:7", "long" to "l:8")
        BackupSubAgentConfig.restoreExactPreferences(originals, prefs)
        assertEquals(mapOf("string" to "old", "boolean" to false, "int" to 7, "long" to 8L), prefs.all)
        assertFalse(prefs.contains(ConversationSubAgentPreferences.RESET_MARKER_KEY))
        val before = prefs.all.toMap()
        assertTrue(runCatching { BackupSubAgentConfig.restoreExactPreferences(mapOf("bad" to "i:no"), prefs) }.isFailure)
        assertEquals(before, prefs.all)
    }

    @Test fun missingNewConversationArchiveAlsoImportsEmptyAndDisabled() {
        val store = ConversationSubAgentPreferences(preferences())
        val archive = BackupSubAgentConfig.archiveForImport(3, null, store)
        val target = SubAgentConfigKey.Conversation("missing")
        assertTrue(store.importOwner(target, archive))
        assertFalse(store.snapshot(target).enabled)
        assertTrue(store.snapshot(target).profiles.isEmpty())
    }

    @Test fun rejectsCorruptionAndUnsupportedVersionsBeforeReplacement() {
        val store = ConversationSubAgentPreferences(preferences())
        val valid = BackupSubAgentConfig.archiveForImport(2, null, store)
        val bad = JSONObject(valid).put("version", 987).toString()
        assertTrue(runCatching { BackupSubAgentConfig.archiveForImport(3, bad, store) }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_seed_v1" to "s:{invalid"), store) }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_owner_v1_c_YQ" to "b:true"), store) }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_owner_v1_c_YQ" to "s:$bad"), store) }.isFailure)
        BackupSubAgentConfig.validatePreferences(mapOf("unrelated" to "s:anything"), store)
    }
}
