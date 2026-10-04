package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentPresetCatalog
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackupSubAgentPresetConfigTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("preset-backup-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun ownerKey(kind: String, id: String) = ConversationSubAgentPreferences.OWNER_PREFIX + kind + "_" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))
    private fun values(preferences: android.content.SharedPreferences) = preferences.all.mapValues { (_, value) -> "s:$value" }

    @Test fun catalogAndPresetPayloadMustMatchExactlyBeforeAnyReplacementOrMigration() {
        val preferences = prefs()
        val store = ConversationSubAgentPreferences(preferences)
        val preset = store.addPreset("完整备份")
        store.applyPreset(SubAgentConfigKey.Conversation("copied"), preset.id)
        val valid = values(preferences)
        assertTrue(BackupSubAgentConfig.containsPreferences(mapOf(SubAgentPresetCatalog.KEY to valid.getValue(SubAgentPresetCatalog.KEY))))
        BackupSubAgentConfig.validatePreferences(valid, store)
        val catalog = JSONObject(valid.getValue(SubAgentPresetCatalog.KEY).substring(2))
        val payload = ownerKey("p", preset.id)
        val duplicate = JSONObject(catalog.toString()).also {
            val entries = it.getJSONArray("presets")
            entries.put(JSONObject(entries.getJSONObject(0).toString()))
        }.toString()
        val malformed = listOf(
            valid + (SubAgentPresetCatalog.KEY to "s:{invalid"),
            valid + (SubAgentPresetCatalog.KEY to "b:true"),
            valid + (SubAgentPresetCatalog.KEY to "s:${JSONObject(catalog.toString()).put("presets", "bad")}"),
            valid + (SubAgentPresetCatalog.KEY to "s:${JSONObject(catalog.toString()).put("version", 999)}"),
            valid + (SubAgentPresetCatalog.KEY to "s:$duplicate"),
            valid + (SubAgentPresetCatalog.KEY to "s:${JSONObject().put("version", 1).put("presets",
                JSONArray().put(JSONObject().put("id", "").put("name", "无ID")))}"),
            valid - payload,
            valid - SubAgentPresetCatalog.KEY,
            valid + (ownerKey("p", "orphan") to valid.getValue(payload)),
            valid + (payload to "s:{invalid"),
            valid + (payload to "b:true"),
            valid + (ConversationSubAgentPreferences.OWNER_PREFIX + "p_***" to valid.getValue(payload)),
            valid + (ConversationSubAgentPreferences.OWNER_PREFIX + "x_YQ" to valid.getValue(payload)),
        )
        val before = preferences.all.toMap()
        val revision = store.revision.value
        malformed.forEach { archive ->
            assertThrows(Exception::class.java) { BackupSubAgentConfig.validatePreferences(archive, store) }
            assertEquals(before, preferences.all)
            assertEquals(revision, store.revision.value)
        }
        val emptyCatalog = mapOf(SubAgentPresetCatalog.KEY to "s:${SubAgentPresetCatalog.encode(emptyList())}")
        BackupSubAgentConfig.validatePreferences(emptyCatalog, store)
        assertEquals(before, preferences.all)
    }

    @Test fun catalogOnlyCorruptionTriggersValidationAndOldArchivesRemainValidWithoutPresets() {
        val preferences = prefs()
        val store = ConversationSubAgentPreferences(preferences)
        val old = BackupSubAgentConfig.archiveForImport(2, null, store)
        assertTrue(preferences.all.isEmpty())
        BackupSubAgentConfig.validatePreferences(mapOf(ownerKey("c", "old") to "s:$old"), store)
        BackupSubAgentConfig.validatePreferences(mapOf(ConversationSubAgentPreferences.SEED_KEY to "s:$old"), store)
        BackupSubAgentConfig.validatePreferences(emptyMap(), store)
        val bad = mapOf(SubAgentPresetCatalog.KEY to "s:{invalid")
        assertTrue(BackupSubAgentConfig.containsPreferences(bad))
        assertThrows(Exception::class.java) { BackupSubAgentConfig.validatePreferences(bad, store) }
        assertTrue(preferences.all.isEmpty())
        assertEquals(0L, store.revision.value)
    }

    @Test fun singleConversationArchiveCanKeepDeletedSourceMetadataWithoutImportingAnyPreset() {
        val sourcePreferences = prefs()
        val source = ConversationSubAgentPreferences(sourcePreferences)
        val preset = source.addPreset("已删除来源")
        val original = SubAgentConfigKey.Conversation("original")
        source.applyPreset(original, preset.id)
        source.removePreset(preset.id)
        val archive = BackupSubAgentConfig.archiveForExport(original.value, source)
        val targetPreferences = prefs()
        val target = ConversationSubAgentPreferences(targetPreferences)
        val resolved = BackupSubAgentConfig.archiveForImport(3, archive, target)
        assertTrue(targetPreferences.all.isEmpty())
        val mapped = SubAgentConfigKey.Conversation("mapped")
        assertTrue(target.importOwner(mapped, resolved))
        assertEquals(source.snapshot(original), target.snapshot(mapped))
        assertEquals(preset.id, target.snapshot(mapped).appliedPresetId)
        assertEquals(preset.name, target.snapshot(mapped).appliedPresetName)
        assertFalse(target.presetExists(preset.id))
        assertFalse(targetPreferences.contains(SubAgentPresetCatalog.KEY))
        BackupSubAgentConfig.validatePreferences(values(targetPreferences), target)
        target.refreshAfterRestore()
        assertEquals(source.snapshot(original), target.snapshot(mapped))
    }

    @Test fun malformedPresentApplicationMetadataIsRejectedButMissingOldFieldsAreAccepted() {
        val store = ConversationSubAgentPreferences(prefs())
        val old = BackupSubAgentConfig.archiveForImport(2, null, store)
        listOf("applied_preset_id", "applied_preset_name", "preset_application_token").forEach { field ->
            assertThrows(Exception::class.java) { store.validateArchive(JSONObject(old).put(field, 7).toString()) }
            assertThrows(Exception::class.java) { store.validateArchive(JSONObject(old).put(field, "").toString()) }
            store.validateArchive(JSONObject(old).put(field, JSONObject.NULL).toString())
        }
        store.validateArchive(old)
    }
}
