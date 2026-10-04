package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentModelDefaults
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentPresetCatalog
import io.github.mangi.eta.agent.delegation.SubAgentProfile
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
        val mapped = BackupSubAgentConfig.archiveForImport(3, archive, store, generation = "1")
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
        val presetOwner = SubAgentConfigKey.Preset(preset.id)
        val model = SubAgentParallelModel("current-provider", "current-api-model")
        val profile = SubAgentProfile(id = "current-agent", name = "当前配置",
            providerId = model.providerId, modelId = "current-selection")
        assertTrue(store.updateConfirmedProfile(presetOwner, profile.id, model) {
            it.copy(profiles = listOf(profile), diagnosticsEnabled = true, parallelLimits = mapOf(model to 4))
        } is ConversationSubAgentPreferences.WriteResult.Saved)
        val owner = SubAgentConfigKey.Conversation("current")
        store.applyPreset(owner, preset.id)
        val draft = store.createDraft(owner)
        val bound = SubAgentConfigKey.Conversation("bound")
        store.bindDraft(draft, bound)
        prefs.edit().putString(ConversationSubAgentPreferences.UI_DRAFT_KEY, draft.value).commit()
        val current = encoded(prefs)
        assertTrue(current.containsKey(SubAgentModelDefaults.KEY))
        assertTrue(current.keys.any { it.startsWith(ConversationSubAgentPreferences.OWNER_PREFIX + "d_") })
        assertTrue(current.keys.any { it.startsWith(ConversationSubAgentPreferences.BIND_PREFIX) })
        val obsolete = mapOf(
            ConversationSubAgentPreferences.SEED_KEY to "s:{obsolete and malformed",
            ConversationSubAgentPreferences.OWNER_PREFIX + "c_b2xk" to "s:{obsolete",
            ConversationSubAgentPreferences.OWNER_PREFIX + "d_b2xk" to "s:{obsolete",
            ConversationSubAgentPreferences.OWNER_PREFIX + "p_b2xk" to "s:{obsolete",
            ConversationSubAgentPreferences.BIND_PREFIX + "c_b2xk" to "s:obsolete",
            SubAgentPresetCatalog.KEY to "s:{obsolete",
            SubAgentModelDefaults.KEY to "s:{obsolete",
            SubAgentPreferences.PROFILES_KEY to "s:{obsolete",
            "agent_collaboration_draft" to "s:true",
            "agent_child_0_provider" to "s:obsolete-provider",
            "agent_child_0_image_resolution" to "s:obsolete-resolution",
            "agent_child_0_enabled" to "b:true",
            model.legacyKey() to "i:99",
            ConversationSubAgentPreferences.UI_DRAFT_KEY to "s:obsolete-draft",
            "provider-token" to "s:restored-token",
            "chat-setting" to "b:true",
        )
        val revision = store.revision.value
        val restored = BackupSubAgentConfig.preferencesForRestore(obsolete, current, store)
        current.forEach { (key, value) -> assertEquals("Current preference lost: $key", value, restored[key]) }
        assertEquals("s:restored-token", restored["provider-token"])
        assertEquals("b:true", restored["chat-setting"])
        // This key is historical in the incoming archive, but a new-generation local tombstone.
        assertEquals(current.getValue(SubAgentPreferences.PROFILES_KEY), restored[SubAgentPreferences.PROFILES_KEY])
        assertEquals(0, JSONObject(restored.getValue(SubAgentPreferences.PROFILES_KEY).substring(2)).getJSONArray("agents").length())
        (obsolete.keys - current.keys - setOf("provider-token", "chat-setting")).forEach { key ->
            assertFalse("Obsolete preference imported: $key", restored.containsKey(key))
        }
        assertEquals("s:1", restored[ConversationSubAgentPreferences.RESET_MARKER_KEY])
        assertEquals(current, encoded(prefs))
        assertEquals(revision, store.revision.value)
    }

    @Test fun oldBackupKeepsExplicitPostResetCompatibilityWritesWithTheirTypes() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        assertTrue(store.resetLegacyConfigurationOnce())
        val profile = SubAgentProfile(id = "confirmed-after-reset", name = "当前兼容配置")
        val model = SubAgentParallelModel("current-provider", "current-model")
        store.saveLegacyConfiguration(profiles = listOf(profile), binding = model, parallelLimit = 3)
        prefs.edit().putBoolean("agent_collaboration_current", false)
            .putInt("agent_child_1_enabled", 0).commit()
        val current = encoded(prefs)
        val incoming = mapOf(
            SubAgentPreferences.PROFILES_KEY to "s:{obsolete",
            model.legacyKey() to "i:99",
            "agent_collaboration_current" to "b:true",
            "agent_child_1_enabled" to "b:true",
        )
        assertEquals(current, BackupSubAgentConfig.preferencesForRestore(incoming, current, store))
        assertEquals("b:false", current["agent_collaboration_current"])
        assertEquals("i:0", current["agent_child_1_enabled"])
    }

    @Test fun oldBackupFiltersOnlyExactHistoricalNamespacesWithoutImportingASeed() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        assertTrue(store.resetLegacyConfigurationOnce())
        val obsolete = buildMap {
            for (slot in 0..3) {
                for (field in listOf("provider", "model", "reasoning", "task_tier", "image_resolution", "enabled")) {
                    put("agent_child_${slot}_$field", "b:true")
                }
            }
            put(SubAgentParallelModel("old-provider", "old-model").legacyKey(), "i:99")
            put("agent_collaboration_old", "b:true")
            put(ConversationSubAgentPreferences.SEED_KEY, "s:{obsolete")
            put(SubAgentPresetCatalog.KEY, "s:{obsolete")
        }
        val unrelated = mapOf(
            "agent_child_4_provider" to "s:unrelated",
            "agent_child_0_provider_token" to "s:secret",
            "agent_model_parallel_not_a_hash" to "i:2",
            "agent_collaboration" to "b:true",
            "agent_parent_model" to "s:parent",
        )
        val current = encoded(prefs)
        val restored = BackupSubAgentConfig.preferencesForRestore(obsolete + unrelated, current, store)
        assertEquals(current + unrelated, restored)
        assertTrue(store.presets().isEmpty())
        assertFalse(store.snapshot(SubAgentConfigKey.Conversation("missing")).enabled)
        assertTrue(store.snapshot(SubAgentConfigKey.Conversation("missing")).profiles.isEmpty())
        assertEquals(current, encoded(prefs))
    }

    @Test fun markedBackupWithoutDefaultsIsValidAndNotOverlaidWithLiveOwners() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        store.resetLegacyConfigurationOnce()
        val owner = SubAgentConfigKey.Conversation("live")
        store.update(owner) { it.copy(diagnosticsEnabled = true) }
        val incoming = mapOf(ConversationSubAgentPreferences.RESET_MARKER_KEY to "s:1", "other" to "i:2")
        val restored = BackupSubAgentConfig.preferencesForRestore(incoming, encoded(prefs), store)
        assertEquals("i:2", restored["other"])
        assertEquals("s:1", restored[ConversationSubAgentPreferences.RESET_MARKER_KEY])
        assertEquals("s:", restored[ConversationSubAgentPreferences.UI_DRAFT_KEY])
        assertEquals(0, JSONObject(restored.getValue(SubAgentPreferences.PROFILES_KEY).substring(2)).getJSONArray("agents").length())
        assertFalse(restored.containsKey(SubAgentModelDefaults.KEY))
        assertFalse(restored.containsKey(ConversationSubAgentPreferences.SEED_KEY))
        assertFalse(restored.containsKey(SubAgentPresetCatalog.KEY))
        assertFalse(restored.keys.any { it.startsWith(ConversationSubAgentPreferences.OWNER_PREFIX) })
    }

    @Test fun markedBackupIgnoresLegacyProfilesButKeepsItsCurrentOwnersAndPointer() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        assertTrue(store.resetLegacyConfigurationOnce())
        val preset = store.addPreset("归档预设")
        val draft = store.createDraft()
        store.applyPreset(draft, preset.id)
        prefs.edit().putString(ConversationSubAgentPreferences.UI_DRAFT_KEY, draft.value).commit()
        val current = encoded(prefs)
        val incoming = current + mapOf(
            SubAgentPreferences.PROFILES_KEY to "s:{obsolete",
            "agent_child_0_enabled" to "b:true",
            "agent_child_0_image_resolution" to "s:obsolete",
        )
        val restored = BackupSubAgentConfig.preferencesForRestore(incoming, current, store)
        assertEquals(current, restored)
        assertEquals("s:${draft.value}", restored[ConversationSubAgentPreferences.UI_DRAFT_KEY])
        assertFalse(restored.containsKey("agent_child_0_enabled"))
        assertFalse(restored.containsKey("agent_child_0_image_resolution"))
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
        val originals = mapOf("string" to "s:old", "boolean" to "b:false", "int" to "i:7", "long" to "l:8",
            SubAgentPreferences.PROFILES_KEY to "s:{original legacy data",
            "agent_child_0_enabled" to "b:false", "agent_collaboration_original" to "i:1")
        BackupSubAgentConfig.restoreExactPreferences(originals, prefs)
        assertEquals(mapOf("string" to "old", "boolean" to false, "int" to 7, "long" to 8L,
            SubAgentPreferences.PROFILES_KEY to "{original legacy data",
            "agent_child_0_enabled" to false, "agent_collaboration_original" to 1), prefs.all)
        assertFalse(prefs.contains(ConversationSubAgentPreferences.RESET_MARKER_KEY))
        assertFalse(prefs.contains(ConversationSubAgentPreferences.UI_DRAFT_KEY))
        val before = prefs.all.toMap()
        assertTrue(runCatching { BackupSubAgentConfig.restoreExactPreferences(mapOf("bad" to "i:no"), prefs) }.isFailure)
        assertEquals(before, prefs.all)
    }

    @Test fun schemaThreeWithoutGenerationIgnoresBothValidAndCorruptArchives() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        val source = SubAgentConfigKey.Conversation("old-source")
        store.update(source) { it.copy(enabled = true, diagnosticsEnabled = true,
            profiles = listOf(SubAgentProfile(id = "old-agent", name = "old", providerId = "provider", modelId = "model"))) }
        val valid = BackupSubAgentConfig.archiveForExport(source.value, store)
        listOf(valid, "{invalid", JSONObject(valid).put("version", 987).toString()).forEachIndexed { index, archive ->
            val before = prefs.all.toMap()
            val revision = store.revision.value
            val resolved = BackupSubAgentConfig.archiveForImport(3, archive, store)
            assertEquals(before, prefs.all)
            assertEquals(revision, store.revision.value)
            val target = SubAgentConfigKey.Conversation("old-import-$index")
            assertTrue(store.importOwner(target, resolved))
            val config = store.snapshot(target)
            assertFalse(config.enabled)
            assertFalse(config.diagnosticsEnabled)
            assertTrue(config.profiles.isEmpty())
            assertTrue(config.parallelLimits.isEmpty())
            assertTrue(config.legacyParallelLimits.isEmpty())
            assertNull(config.appliedPresetId)
        }
    }

    @Test fun unsupportedGenerationRejectsBeforeValidationOrOwnerWrites() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        val fallback = BackupSubAgentConfig.archiveForImport(3, null, store)
        val before = prefs.all.toMap()
        val revision = store.revision.value
        listOf("", "0", "2", "s:1", " 1").forEach { generation ->
            listOf<String?>(null, fallback, "{invalid").forEach { archive ->
                val failure = runCatching {
                    BackupSubAgentConfig.archiveForImport(3, archive, store, generation = generation)
                }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertEquals("不支持的会话子代理配置代际", failure?.message)
                assertEquals(before, prefs.all)
                assertEquals(revision, store.revision.value)
            }
        }
    }

    @Test fun missingNewConversationArchiveAlsoImportsEmptyAndDisabled() {
        val store = ConversationSubAgentPreferences(preferences())
        val archive = BackupSubAgentConfig.archiveForImport(3, null, store, generation = "1")
        val target = SubAgentConfigKey.Conversation("missing")
        assertTrue(store.importOwner(target, archive))
        assertFalse(store.snapshot(target).enabled)
        assertTrue(store.snapshot(target).profiles.isEmpty())
    }

    @Test fun rejectsCorruptionAndUnsupportedVersionsBeforeReplacement() {
        val store = ConversationSubAgentPreferences(preferences())
        val valid = BackupSubAgentConfig.archiveForImport(2, null, store)
        val bad = JSONObject(valid).put("version", 987).toString()
        assertTrue(runCatching { BackupSubAgentConfig.archiveForImport(3, bad, store, generation = "1") }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.archiveForImport(3, "{invalid", store, generation = "1") }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_seed_v1" to "s:{invalid"), store) }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_owner_v1_c_YQ" to "b:true"), store) }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_owner_v1_c_YQ" to "s:$bad"), store) }.isFailure)
        BackupSubAgentConfig.validatePreferences(mapOf("unrelated" to "s:anything"), store)
    }
}
