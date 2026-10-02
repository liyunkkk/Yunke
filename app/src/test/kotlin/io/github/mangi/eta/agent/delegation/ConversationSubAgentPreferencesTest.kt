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

    @Test fun previewUsesTheSameLegacySelectionWithoutPersistingSeedOrRevision() {
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
        assertEquals(3, preview.parallelLimit(pool))
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

    @Test fun seedFreezesOldValuesAndExistingOwnerIsNeverOverwritten() {
        val prefs = prefs()
        prefs.edit().putString(SubAgentPreferences.PROFILES_KEY,
            JSONObject().put("version", 1).put("agents", JSONArray().put(profile().toJson())).toString())
            .putString(pool.legacyKey(), "3").putString("agent_collaboration_old", "false").commit()
        val repo = ConversationSubAgentPreferences(prefs)
        assertFalse(repo.snapshot(c("old")).enabled)
        assertEquals(3, repo.snapshot(c("old")).parallelLimit(pool))
        assertEquals(null, repo.snapshot(c("old")).parallelLimits[SubAgentParallelModel("p", "selection")])
        repo.createConversation(c("old"))
        prefs.edit().putString(SubAgentPreferences.PROFILES_KEY, "bad JSON").putString(pool.legacyKey(), "9").commit()
        assertEquals(3, repo.snapshot(c("other")).parallelLimit(pool))
        assertFalse(repo.snapshot(c("old")).enabled)
        val changed = repo.update(c("old")) { it.copy(profiles = emptyList()) }
        assertTrue(changed is ConversationSubAgentPreferences.WriteResult.Saved)
        repo.createConversation(c("old"), c("other"))
        assertTrue(repo.snapshot(c("old")).profiles.isEmpty())
    }
    @Test fun draftsCopyDeeplyAndBindingDoesNotDeleteUntilConfirmed() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val source = c("source")
        val memory = mutableMapOf("p\u0000selection" to ReasoningEffort.HIGH)
        val profiles = mutableListOf(profile().copy(reasoningByModel = memory))
        val limits = mutableMapOf(pool to 2)
        repo.update(source) { it.copy(profiles = profiles, parallelLimits = limits, diagnosticsEnabled = true) }
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
        b.update(owner) { it.copy(diagnosticsEnabled = true) }
        assertFalse(a.snapshot(owner).enabled)
        assertTrue(a.snapshot(owner).diagnosticsEnabled)
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
        assertTrue(repo.snapshot(c("running")).enabled)
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
    @Test fun corruptOldListIsNotAnEmptyListAndDraftsNeverShareNullKey() {
        val prefs = prefs()
        prefs.edit().putString(SubAgentPreferences.PROFILES_KEY, "garbage").commit()
        val repo = ConversationSubAgentPreferences(prefs)
        assertThrows(Exception::class.java) { repo.snapshot(c("x")) }
        prefs.edit().putString(SubAgentPreferences.PROFILES_KEY,
            JSONObject().put("version", 1).put("agents", JSONArray()).toString()).commit()
        assertTrue(repo.snapshot(c("x")).profiles.isEmpty())
        val a = repo.createDraft(); val b = repo.createDraft()
        assertNotEquals(a, b)
        repo.update(a) { it.copy(enabled = false) }
        assertTrue(repo.snapshot(b).enabled)
    }
}
