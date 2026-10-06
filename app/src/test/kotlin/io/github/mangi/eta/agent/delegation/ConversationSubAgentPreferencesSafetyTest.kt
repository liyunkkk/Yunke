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

    @Test fun failedPresetCreationFencesCatalogAndPayloadUntilOriginalsAreRecovered() {
        val prefs = FalseAfterMemory(prefs())
        val a = ConversationSubAgentPreferences(prefs)
        val b = ConversationSubAgentPreferences(prefs)
        val before = prefs.all.toMap()
        val revision = a.revision.value
        prefs.failures = 2
        assertThrows(IllegalStateException::class.java) { a.addPreset("Explicit group") }
        assertEquals(revision, b.revision.value)
        assertThrows(IllegalStateException::class.java) { b.presetExists(SubAgentPresetCatalog.DEFAULT_ID) }
        assertThrows(IllegalStateException::class.java) { b.addPreset("不能绕过") }
        assertThrows(IllegalStateException::class.java) { b.presets() }
        assertTrue(a.recoverDurability())
        assertEquals(before, prefs.all)
        assertEquals(revision, a.revision.value)
        assertTrue(b.presets().isEmpty())
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
        val base = JSONObject().put("version", 1).put("enabled", true)
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

    @Test fun resetIsTypedWhitelistedAtomicAndRunsOnlyOnce() {
        val storage = FalseAfterMemory(prefs())
        val originalList = org.json.JSONObject().put("version", 1).put("agents", org.json.JSONArray()).toString()
        val pool = SubAgentParallelModel("p", "api")
        storage.edit().putBoolean("agent_child_0_enabled", true).putInt(pool.legacyKey(), 4)
            .putBoolean("agent_collaboration_old", true).putString(SubAgentPreferences.PROFILES_KEY, originalList)
            .putString(ConversationSubAgentPreferences.UI_DRAFT_KEY, "old-draft")
            .putString("provider_api_key", "preserved").putString("chat_history", "preserved").commit()
        val repo = ConversationSubAgentPreferences(storage)
        assertFalse(repo.isConfigurationResetComplete())
        val before = storage.all.toMap(); val revision = repo.revision.value
        storage.failures = 2
        assertThrows(IllegalStateException::class.java) { repo.resetLegacyConfigurationOnce() }
        assertThrows(IllegalStateException::class.java) { repo.isConfigurationResetComplete() }
        assertEquals(revision, repo.revision.value)
        assertTrue(repo.recoverDurability()); assertEquals(before, storage.all)
        assertTrue(repo.resetLegacyConfigurationOnce()); assertTrue(repo.isConfigurationResetComplete())
        assertEquals("1", storage.getString(ConversationSubAgentPreferences.RESET_MARKER_KEY, null))
        assertEquals("", storage.getString(ConversationSubAgentPreferences.UI_DRAFT_KEY, null))
        assertEquals(originalList, storage.getString(SubAgentPreferences.PROFILES_KEY, null))
        assertEquals("preserved", storage.getString("provider_api_key", null))
        assertEquals("preserved", storage.getString("chat_history", null))
        assertFalse(storage.contains("agent_child_0_enabled")); assertFalse(storage.contains(pool.legacyKey()))
        assertFalse(storage.contains("agent_collaboration_old"))
        assertFalse(repo.snapshot(c("new")).enabled); assertTrue(repo.snapshot(c("new")).profiles.isEmpty())
        assertTrue(repo.presets().isEmpty()); SubAgentModelDefaults.validate(storage.getString(SubAgentModelDefaults.KEY, null)!!)
        val owner = repo.createDraft(); repo.update(owner) { it.copy(profiles = listOf(SubAgentProfile("new", "New")), enabled = true) }
        repo.addPreset("New preset")
        val after = storage.all.toMap(); val afterRevision = repo.revision.value
        assertTrue(ConversationSubAgentPreferences(storage).resetLegacyConfigurationOnce())
        assertEquals(after, storage.all); assertEquals(afterRevision, repo.revision.value)
    }

    @Test fun confirmationCommitFailureRollsBackOwnerAndDefaultsTogether() {
        val storage = FalseAfterMemory(prefs()); val repo = ConversationSubAgentPreferences(storage)
        val owner = c("atomic"); val model = SubAgentParallelModel("p", "api")
        val profile = SubAgentProfile("worker", "Worker", providerId = "p", modelId = "selection")
        repo.updateConfirmedProfile(owner, profile.id, model) { it.copy(profiles = listOf(profile), parallelLimits = mapOf(model to 0)) }
        val before = storage.all.toMap(); val revision = repo.revision(owner).value
        storage.failures = 2
        assertThrows(IllegalStateException::class.java) {
            repo.updateConfirmedProfile(owner, profile.id, model) { it.copy(profiles = listOf(profile.copy(enabled = false)), parallelLimits = mapOf(model to 7)) }
        }
        assertEquals(revision, repo.revision(owner).value)
        assertThrows(IllegalStateException::class.java) { repo.modelDefaults(profile) }
        assertTrue(repo.recoverDurability()); assertEquals(before, storage.all)
        assertEquals(0, repo.modelDefaults(profile)?.parallelLimit)
        assertTrue(repo.snapshot(owner).profiles.single().enabled)
    }

    @Test fun missingDraftAndStaleOwnerFenceRejectBeforeChangeWithoutCollectingDefaults() {
        val storage = prefs(); val repo = ConversationSubAgentPreferences(storage); val other = ConversationSubAgentPreferences(storage)
        val owner = repo.createDraft(); val archive = repo.export(owner); val captured = repo.ownerState(owner)
        val model = SubAgentParallelModel("p", "api")
        val profile = SubAgentProfile("worker", "Worker", providerId = "p", modelId = "selection")
        fun rejected(expected: ConversationSubAgentPreferences.OwnerState? = null) {
            val before = storage.all.toMap(); val revision = repo.revision.value; val ownerRevision = repo.revision(owner).value
            var changed = false
            assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected,
                repo.updateConfirmedProfile(owner, profile.id, model, expectedOwnerState = expected) {
                    changed = true
                    it.copy(profiles = listOf(profile))
                })
            assertFalse(changed); assertEquals(before, storage.all); assertEquals(revision, repo.revision.value)
            assertEquals(ownerRevision, repo.revision(owner).value)
            assertFalse(storage.contains(SubAgentModelDefaults.KEY)); assertNull(repo.modelDefaults(profile))
        }
        assertTrue(other.delete(owner))
        rejected() // Missing Draft must reject even synchronous callers without a fence.
        rejected(captured)
        assertTrue(other.importOwner(owner, archive))
        assertTrue(repo.ownerState(owner).exists); assertNotEquals(captured, repo.ownerState(owner))
        rejected(captured) // Identical payload does not hide the delete/recreate epoch.
    }

    @Test fun absentConversationOwnerFenceIsReadOnlyAndStillAllowsFirstExplicitConfirmation() {
        val storage = prefs(); val repo = ConversationSubAgentPreferences(storage); val owner = c("old-unsaved")
        val before = storage.all.toMap(); val revision = repo.revision.value
        val captured = repo.ownerState(owner)
        assertFalse(captured.exists); assertTrue(repo.snapshot(owner).profiles.isEmpty())
        assertEquals(before, storage.all); assertEquals(revision, repo.revision.value)
        val model = SubAgentParallelModel("p", "api")
        val profile = SubAgentProfile("worker", "Worker", providerId = "p", modelId = "selection")
        assertTrue(repo.updateConfirmedProfile(owner, profile.id, model, expectedOwnerState = captured) {
            it.copy(profiles = listOf(profile))
        } is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(repo.ownerState(owner).exists); assertEquals(profile, repo.snapshot(owner).profiles.single())
        assertNotNull(repo.modelDefaults(profile))
    }

    @Test fun archivesStrictlyValidateDefaultsAndResetMarkerWithoutRequiringDefaults() {
        val repo = ConversationSubAgentPreferences(prefs())
        repo.validatePreferenceArchives(mapOf(ConversationSubAgentPreferences.RESET_MARKER_KEY to "1"))
        repo.validatePreferenceArchives(mapOf(SubAgentModelDefaults.KEY to SubAgentModelDefaults.encode(emptyMap())))
        assertThrows(IllegalArgumentException::class.java) {
            repo.validatePreferenceArchives(mapOf(ConversationSubAgentPreferences.RESET_MARKER_KEY to "true"))
        }
        assertThrows(Exception::class.java) { repo.validatePreferenceArchives(mapOf(SubAgentModelDefaults.KEY to "{}")) }
        val entry = SubAgentModelDefaults.confirmed(SubAgentProfile("w", "W", providerId = "p", modelId = "m"), null,
            SubAgentParallelModel("p", "api"), 0)
        val raw = org.json.JSONObject(SubAgentModelDefaults.encode(mapOf(entry.key to entry)))
        val bad = raw.getJSONArray("models").getJSONObject(0)
        bad.put("parallel_limit", "0")
        assertThrows(Exception::class.java) { repo.validatePreferenceArchives(mapOf(SubAgentModelDefaults.KEY to raw.toString())) }
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
