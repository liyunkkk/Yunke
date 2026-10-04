package io.github.mangi.eta.agent.delegation

import android.app.Application
import android.content.Context
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class ConversationSubAgentPreferencesTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("subagents-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun c(id: String) = SubAgentConfigKey.Conversation(id)
    private val pool = SubAgentParallelModel("p", "api-model")
    private fun profile() = SubAgentProfile("worker", "name", reasoning = ReasoningEffort.HIGH,
        providerId = "p", modelId = "selection", reasoningByModel = mutableMapOf("p\u0000selection" to ReasoningEffort.HIGH))

    @Test fun previewIgnoresLegacySelectionWithoutPersistingSeedOrRevision() {
        val prefs = prefs()
        prefs.edit().putString(SubAgentPreferences.PROFILES_KEY,
            JSONObject().put("version", 1).put("agents", JSONArray().put(profile().toJson())).toString())
            .putString(pool.legacyKey(), "3").putString("agent_collaboration_old", "false").commit()
        val repo = ConversationSubAgentPreferences(prefs)
        val before = prefs.all.toMap()
        val revision = repo.revision.value
        val preview = repo.previewSnapshot(c("old"))
        assertEquals(before, prefs.all)
        assertEquals(revision, repo.revision.value)
        assertFalse(prefs.contains(ConversationSubAgentPreferences.SEED_KEY))
        assertFalse(preview.enabled)
        assertTrue(preview.profiles.isEmpty())
        assertEquals(1, preview.parallelLimit(pool))
        assertEquals(repo.snapshot(c("old")), preview)
    }

    @Test fun previewFollowsDraftAndPromotedOwnerInsteadOfGlobalProfiles() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val source = c("source")
        repo.update(source) { it.copy(profiles = listOf(profile()), parallelLimits = mapOf(pool to 7)) }
        val draft = repo.createDraft(source)
        repo.update(draft) { it.copy(profiles = listOf(profile().copy(id = "draft-worker"))) }
        val promoted = c("promoted")
        repo.bindDraft(draft, promoted)
        repo.confirmBoundDraft(draft, promoted)
        prefs.edit().putString(SubAgentPreferences.PROFILES_KEY, "broken global profiles").commit()
        val before = prefs.all.toMap()
        assertEquals("draft-worker", repo.previewSnapshot(promoted).profiles.single().id)
        assertEquals(7, repo.previewSnapshot(promoted).parallelLimit(pool))
        assertEquals("worker", repo.previewSnapshot(source).profiles.single().id)
        assertEquals(before, prefs.all)
    }

    @Test fun explicitSeedStillReadsButIsNotAutomaticallyImportedIntoPresets() {
        val prefs = prefs(); val repo = ConversationSubAgentPreferences(prefs)
        repo.update(c("source")) { it.copy(profiles = listOf(profile()), parallelLimits = mapOf(pool to 3), enabled = true) }
        prefs.edit().putString(ConversationSubAgentPreferences.SEED_KEY, repo.export(c("source"))).commit()
        repo.createConversation(c("old"))
        assertEquals(3, repo.snapshot(c("old")).parallelLimit(pool))
        assertEquals("worker", repo.snapshot(c("old")).profiles.single().id)
        assertTrue(repo.presets().isEmpty())
        prefs.edit().putString(ConversationSubAgentPreferences.SEED_KEY, "bad JSON").commit()
        assertEquals(3, repo.snapshot(c("old")).parallelLimit(pool))
        assertThrows(Exception::class.java) { repo.snapshot(c("new")) }
        assertTrue(repo.presets().isEmpty())
    }
    @Test fun draftsCopyDeeplyAndBindingDoesNotDeleteUntilConfirmed() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val source = c("source")
        val memory = mutableMapOf("p\u0000selection" to ReasoningEffort.HIGH)
        val profiles = mutableListOf(profile().copy(reasoningByModel = memory))
        val limits = mutableMapOf(pool to 2)
        repo.update(source) { it.copy(profiles = profiles, parallelLimits = limits, enabled = true) }
        val draft = repo.createDraft(source)
        val second = repo.createDraft(source)
        profiles.clear(); memory.clear(); limits.clear()
        assertEquals(ReasoningEffort.HIGH, repo.snapshot(draft).profiles.single().reasoningByModel["p\u0000selection"])
        val bound = c("bound")
        repo.bindDraft(draft, bound)
        assertEquals(repo.snapshot(draft), repo.bindDraft(draft, bound))
        repo.update(draft) { it.copy(enabled = false, parallelLimits = mapOf(pool to 8)) }
        assertTrue(repo.snapshot(bound).enabled)
        assertEquals(2, repo.snapshot(bound).parallelLimits[pool])
        assertEquals(2, repo.snapshot(second).parallelLimits[pool])
        assertNotEquals(repo.snapshot(bound).poolKey(bound, pool), repo.snapshot(second).poolKey(second, pool))
        assertFalse(repo.confirmBoundDraft(second, bound))
        assertThrows(Exception::class.java) { repo.bindDraft(second, bound) }
        assertFalse(repo.confirmBoundDraft(second, c("not-bound")))
        assertTrue(repo.confirmBoundDraft(draft, bound))
        assertFalse(repo.confirmBoundDraft(draft, bound))
    }
    @Test fun instancesShareRevisionAndSerializeWrites() {
        val prefs = prefs()
        val a = ConversationSubAgentPreferences(prefs)
        val b = ConversationSubAgentPreferences(prefs)
        val owner = c("same")
        a.update(owner) { it.copy(enabled = false) }
        assertEquals(a.revision.value, b.revision.value)
        b.update(owner) { it.copy(parallelLimits = mapOf(pool to 3)) }
        assertFalse(a.snapshot(owner).enabled)
        assertEquals(3, a.snapshot(owner).parallelLimit(pool))
    }
    @Test fun rejectionAndRevisionAreOwnerScoped() = runBlocking {
        val repo = ConversationSubAgentPreferences(prefs()) { it != c("running") }
        val before = repo.snapshot(c("running"))
        val version = repo.revision.value
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected,
            repo.update(c("running")) { it.copy(enabled = false) })
        assertEquals(version, repo.revision.value)
        assertEquals(before, repo.snapshot(c("running")))
        repo.update(c("idle")) { it.copy(enabled = false) }
        assertFalse(repo.flow(c("idle")).first().enabled)
        assertFalse(repo.snapshot(c("running")).enabled)
    }
    @Test fun invalidArchivesCannotEraseExplicitEmptyOrExistingConfig() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        repo.update(c("empty")) { it.copy(profiles = emptyList(), enabled = false) }
        val archive = repo.export(c("empty"))
        assertTrue(repo.importOwner(c("mapped"), archive))
        assertFalse(repo.snapshot(c("mapped")).enabled)
        assertTrue(repo.snapshot(c("mapped")).profiles.isEmpty())
        assertFalse(repo.importOwner(c("mapped"), repo.export(c("another"))))
        assertTrue(repo.snapshot(c("mapped")).profiles.isEmpty())
        assertTrue(repo.importOwner(c("mapped"), repo.export(c("another")), overwrite = true))
        val broken = JSONObject(archive).put("agents", "not array").toString()
        assertThrows(Exception::class.java) { repo.importOwner(c("empty"), broken, overwrite = true) }
        assertEquals(archive, repo.export(c("empty")))
        prefs.edit().putString(ConversationSubAgentPreferences.OWNER_PREFIX + "c_bad", "{").commit()
        assertThrows(Exception::class.java) { repo.refreshAfterRestore() }
        prefs.edit().remove(ConversationSubAgentPreferences.OWNER_PREFIX + "c_bad").commit()
        val revision = repo.revision.value
        repo.refreshAfterRestore()
        assertTrue(repo.revision.value > revision)
    }
    @Test fun retiredDiagnosticKeyIsIgnoredAcrossReadWriteImportAndExport() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val source = c("diagnostic-source")
        repo.update(source) { it.copy(enabled = true, profiles = listOf(profile()), parallelLimits = mapOf(pool to 2)) }
        val archive = repo.export(source)
        assertFalse(JSONObject(archive).has("diagnostics_enabled"))
        val expected = repo.snapshot(source)
        listOf<Any?>(null, true, false, "not-a-switch", JSONObject.NULL).forEachIndexed { index, legacyValue ->
            val legacy = JSONObject(archive)
            if (legacyValue != null) legacy.put("diagnostics_enabled", legacyValue)
            repo.validateArchive(legacy.toString())
            val target = c("diagnostic-import-$index")
            assertTrue(repo.importOwner(target, legacy.toString()))
            assertEquals(expected, repo.snapshot(target))
            assertFalse(JSONObject(repo.export(target)).has("diagnostics_enabled"))
            repo.update(target) { it.copy(enabled = false) }
            assertFalse(JSONObject(repo.export(target)).has("diagnostics_enabled"))
        }
        // Old owner/seed values stay readable without a diagnostic migration or reset.
        val legacy = JSONObject(archive).put("diagnostics_enabled", true).toString()
        val ownerKey = ConversationSubAgentPreferences.OWNER_PREFIX + "c_" +
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(source.value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(ownerKey, legacy).putString(ConversationSubAgentPreferences.SEED_KEY, legacy).commit()
        val before = prefs.all.toMap()
        assertEquals(expected, repo.snapshot(source))
        assertEquals(expected, repo.snapshot(c("diagnostic-seed")))
        assertEquals(before, prefs.all)
        val draft = repo.createDraft(source)
        assertEquals(expected, repo.snapshot(draft))
        assertFalse(JSONObject(repo.export(draft)).has("diagnostics_enabled"))
    }

    @Test fun obsoleteListIsNotMigratedAndDraftsNeverShareNullKey() {
        val prefs = prefs()
        prefs.edit().putString(SubAgentPreferences.PROFILES_KEY, "garbage").commit()
        val repo = ConversationSubAgentPreferences(prefs)
        val before = prefs.all.toMap()
        assertTrue(repo.snapshot(c("x")).profiles.isEmpty())
        assertFalse(repo.snapshot(c("x")).enabled)
        assertEquals(before, prefs.all) // Reads do not reset or overwrite even obsolete data.
        val a = repo.createDraft(); val b = repo.createDraft()
        assertNotEquals(a, b)
        repo.update(a) { it.copy(enabled = true) }
        assertFalse(repo.snapshot(b).enabled)
    }
}
