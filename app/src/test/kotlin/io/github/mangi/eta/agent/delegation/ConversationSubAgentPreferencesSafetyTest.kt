package io.github.mangi.eta.agent.delegation

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConversationSubAgentPreferencesSafetyTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("safety-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun c(id: String) = SubAgentConfigKey.Conversation(id)

    /** Models Android editors which change memory despite returning false, including failed rollback. */
    private class FalseAfterMemory(private val real: SharedPreferences) : SharedPreferences by real {
        var failures = 0
        override fun edit(): SharedPreferences.Editor {
            val edit = real.edit()
            return object : SharedPreferences.Editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { edit.putString(key, value); return this }
                override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { edit.putStringSet(key, values); return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor { edit.putInt(key, value); return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { edit.putLong(key, value); return this }
                override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { edit.putFloat(key, value); return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { edit.putBoolean(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { edit.remove(key); return this }
                override fun clear(): SharedPreferences.Editor { edit.clear(); return this }
                override fun apply() { edit.apply() }
                override fun commit(): Boolean {
                    edit.commit()
                    if (failures > 0) { failures--; return false }
                    return true
                }
            }
        }
    }

    @Test fun invalidTypedProfilesNeverReplaceReadableOwnerOrAdvanceItsRevision() {
        val repository = ConversationSubAgentPreferences(prefs())
        val owner = c("typed")
        repository.update(owner) { it.copy(profiles = listOf(SubAgentProfile("id", "name"))) }
        val before = repository.export(owner)
        val version = repository.revision(owner).value
        val invalid = listOf(
            SubAgentProfile("id", "name", imageResolution = "invalid"),
            SubAgentProfile("id", "name", reasoningByModel = mapOf("missing-delimiter" to io.github.mangi.eta.data.model.ReasoningEffort.OFF)),
            SubAgentProfile("id", "name", reasoningByModel = mapOf("p\u0000m\u0000n" to io.github.mangi.eta.data.model.ReasoningEffort.OFF)),
        )
        invalid.forEach { profile ->
            assertThrows(IllegalArgumentException::class.java) {
                repository.update(owner) { it.copy(profiles = listOf(profile)) }
            }
            assertEquals(before, repository.export(owner))
            assertEquals(version, repository.revision(owner).value)
        }
        val saved = repository.update(owner) { it.copy(enabled = false) } as ConversationSubAgentPreferences.WriteResult.Saved
        assertEquals(saved.config, repository.snapshot(owner))
    }

    @Test fun failedUpdateAndFailedRollbackFenceEveryInstanceUntilExplicitRecovery() {
        val prefs = FalseAfterMemory(prefs())
        val a = ConversationSubAgentPreferences(prefs)
        val b = ConversationSubAgentPreferences(prefs)
        a.update(c("a")) { it.copy(enabled = false) }
        val before = a.export(c("a"))
        val aVersion = a.revision(c("a")).value
        prefs.failures = 2
        assertThrows(IllegalStateException::class.java) { a.update(c("a")) { it.copy(enabled = true) } }
        assertEquals(aVersion, b.revision(c("a")).value)
        assertThrows(IllegalStateException::class.java) { b.snapshot(c("a")) }
        assertThrows(IllegalStateException::class.java) { b.delete(c("a")) }
        prefs.failures = 1
        assertFalse(b.recoverDurability())
        assertThrows(IllegalStateException::class.java) { a.export(c("a")) }
        assertTrue(a.recoverDurability())
        assertEquals(before, b.export(c("a")))
    }

    @Test fun failedPresetMigrationFencesCatalogAndPayloadUntilOriginalsAreRecovered() {
        val prefs = FalseAfterMemory(prefs())
        val a = ConversationSubAgentPreferences(prefs)
        val b = ConversationSubAgentPreferences(prefs)
        val before = prefs.all.toMap()
        val revision = a.revision.value
        prefs.failures = 2
        assertThrows(IllegalStateException::class.java) { a.presets() }
        assertEquals(revision, b.revision.value)
        assertThrows(IllegalStateException::class.java) { b.presetExists(SubAgentPresetCatalog.DEFAULT_ID) }
        assertThrows(IllegalStateException::class.java) { b.addPreset("不能绕过") }
        assertThrows(IllegalStateException::class.java) { b.presets() }
        assertTrue(a.recoverDurability())
        assertEquals(before, prefs.all)
        assertEquals(revision, a.revision.value)
        assertEquals(1, b.presets().size)
        b.validateRestoredPreferences()
    }

    @Test fun failedPresetAddRenameDeleteEditAndApplyShareFenceAndKeepOriginalRevisions() {
        val prefs = FalseAfterMemory(prefs())
        val a = ConversationSubAgentPreferences(prefs)
        val b = ConversationSubAgentPreferences(prefs)
        val preset = a.addPreset("安全组")
        val owner = c("target")
        a.createConversation(owner)
        val presetOwner = SubAgentConfigKey.Preset(preset.id)
        val operations: List<() -> Unit> = listOf(
            { a.addPreset("新组"); Unit },
            { a.renamePreset(preset.id, "重命名"); Unit },
            { a.removePreset(preset.id); Unit },
            { a.update(presetOwner) { it.copy(enabled = false) }; Unit },
            { a.applyPreset(owner, preset.id); Unit },
        )
        operations.forEach { operation ->
            val before = prefs.all.toMap()
            val revision = a.revision.value
            val targetRevision = a.revision(owner).value
            val presetRevision = a.revision(presetOwner).value
            prefs.failures = 2
            assertThrows(IllegalStateException::class.java) { operation() }
            assertEquals(revision, b.revision.value)
            assertEquals(targetRevision, b.revision(owner).value)
            assertEquals(presetRevision, b.revision(presetOwner).value)
            assertThrows(IllegalStateException::class.java) { b.presets() }
            assertThrows(IllegalStateException::class.java) { b.applyPreset(owner, preset.id) }
            assertTrue(b.recoverDurability())
            assertEquals(before, prefs.all)
            assertEquals(revision, a.revision.value)
            a.validateRestoredPreferences()
        }
    }

    @Test fun failedBindAndConfirmNeverDeleteUniqueDraft() {
        val prefs = FalseAfterMemory(prefs())
        val repo = ConversationSubAgentPreferences(prefs)
        val draft = repo.createDraft()
        val conversation = c("bound")
        prefs.failures = 2
        assertThrows(IllegalStateException::class.java) { repo.bindDraft(draft, conversation) }
        assertThrows(IllegalStateException::class.java) { repo.confirmBoundDraft(draft, conversation) }
        assertTrue(repo.recoverDurability())
        assertFalse(repo.confirmBoundDraft(draft, conversation))
        repo.bindDraft(draft, conversation)
        prefs.failures = 2
        assertThrows(IllegalStateException::class.java) { repo.confirmBoundDraft(draft, conversation) }
        assertThrows(IllegalStateException::class.java) { repo.confirmBoundDraft(draft, conversation) }
        assertTrue(repo.recoverDurability())
        assertTrue(repo.confirmBoundDraft(draft, conversation))
    }

    @Test fun strictArchiveRejectsWrongTypesAndBadNestedFieldsBeforeOverwriteWithoutWritingSeed() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val untouched = prefs.all.toMap()
        val base = JSONObject().put("version", 1).put("enabled", true).put("diagnostics_enabled", false)
            .put("agents", org.json.JSONArray().put(SubAgentProfile("id", "name").toJson()))
            .put("parallel_limits", org.json.JSONArray()).put("legacy_parallel_limits", org.json.JSONArray())
        repo.validateArchive(base.toString())
        assertEquals(untouched, prefs.all)
        repo.importOwner(c("x"), base.toString())
        val original = repo.export(c("x"))
        val broken = listOf(
            JSONObject(base.toString()).put("enabled", "false"),
            JSONObject(base.toString()).put("legacy_parallel_limits", "invalid"),
            JSONObject(base.toString()).put("parallel_limits", org.json.JSONArray().put(JSONObject().put("provider", "p").put("api_model", "m").put("limit", "2"))),
            JSONObject(base.toString()).put("agents", org.json.JSONArray().put(JSONObject(SubAgentProfile("id", "name").toJson().toString()).put("reasoning_memory", "invalid"))),
            JSONObject(base.toString()).put("agents", org.json.JSONArray().put(JSONObject(SubAgentProfile("id", "name").toJson().toString()).put("enabled", "true"))),
            JSONObject(base.toString()).put("agents", org.json.JSONArray().put(JSONObject(SubAgentProfile("id", "name").toJson().toString()).put("reasoning_memory", org.json.JSONArray().put(JSONObject().put("provider", "p").put("model", "m").put("reasoning", "unknown")))))
        )
        broken.forEach { bad ->
            assertThrows(Exception::class.java) { repo.validateArchive(bad.toString()) }
            assertThrows(Exception::class.java) { repo.importOwner(c("x"), bad.toString(), overwrite = true) }
            assertEquals(original, repo.export(c("x")))
        }
    }

    @Test fun revisionsAreOwnerSpecificAcrossInstancesAndBindingIsOneToOne() {
        val prefs = prefs()
        val a = ConversationSubAgentPreferences(prefs)
        val b = ConversationSubAgentPreferences(prefs)
        val first = c("first"); val second = c("second")
        val firstVersion = a.revision(first).value
        val saved = a.update(second) { it.copy(enabled = false) } as ConversationSubAgentPreferences.WriteResult.Saved
        assertEquals(saved.revision, b.revision(second).value)
        assertEquals(firstVersion, a.revision(first).value)
        val draft = a.createDraft()
        a.bindDraft(draft, first)
        assertThrows(IllegalArgumentException::class.java) { b.bindDraft(draft, second) }
        assertThrows(IllegalArgumentException::class.java) { b.delete(draft) }
        assertTrue(a.delete(first))
        assertFalse(b.confirmBoundDraft(draft, first))
        assertThrows(IllegalArgumentException::class.java) { b.bindDraft(draft, second) }
        assertEquals(saved.config, b.snapshot(second))
        assertTrue(b.delete(second))
        b.bindDraft(draft, second)
        assertTrue(a.confirmBoundDraft(draft, second))
    }

    @Test fun restoreRejectsDanglingAndSharedLegacyBindings() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val draft = repo.createDraft()
        repo.bindDraft(draft, c("one"))
        repo.createConversation(c("two"))
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("two".toByteArray())
        val name = "agent_conversation_child_binding_v1_c_$encoded"
        prefs.edit().putString(name, draft.value).commit()
        assertThrows(Exception::class.java) { repo.validateRestoredPreferences() }
        assertThrows(Exception::class.java) { repo.confirmBoundDraft(draft, c("one")) }
        prefs.edit().remove(name).commit()
        repo.validateRestoredPreferences()
        assertTrue(repo.confirmBoundDraft(draft, c("one")))
    }
}
