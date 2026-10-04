package io.github.mangi.eta.agent.delegation

import android.app.Application
import android.content.Context
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.Base64
import java.util.UUID
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
class SubAgentPresetCatalogTest {
    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("presets-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private fun c(id: String) = SubAgentConfigKey.Conversation(id)
    private fun p(id: String) = SubAgentConfigKey.Preset(id)
    private val model = SubAgentParallelModel("provider", "api-model")
    private fun payloadKey(id: String) = ConversationSubAgentPreferences.OWNER_PREFIX + "p_" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))

    private fun fullConfig(): ConversationSubAgentConfig = ConversationSubAgentConfig(
        profiles = listOf(
            SubAgentProfile("worker", "执行", role = "implementation", enabled = false,
                providerId = "provider", modelId = "selection", tier = SubAgentTaskTier.COMPLEX,
                reasoning = ReasoningEffort.HIGH,
                reasoningByModel = mutableMapOf("provider\u0000selection" to ReasoningEffort.HIGH,
                    "other\u0000selection-2" to ReasoningEffort.OFF),
                gptSpeedByModel = mutableMapOf("provider\u0000selection" to GptSpeedMode.ULTRA_FAST)),
            SubAgentProfile("review", "审查", role = "review", providerId = "other", modelId = "selection-2"),
            SubAgentProfile("image", "图片", role = "image_generation", providerId = "media", modelId = "image-model",
                imageResolution = io.github.mangi.eta.agent.model.ImageResolutionTier.values.first()),
            SubAgentProfile("video", "视频", role = "video_generation", providerId = "media", modelId = "video-model"),
        ),
        enabled = false,
        parallelLimits = mutableMapOf(model to 0, SubAgentParallelModel("other", "api-model-2") to 5),
        diagnosticsEnabled = true,
        legacyParallelLimits = mutableMapOf(model.legacyKey() to 3),
        appliedPresetId = "old-source", appliedPresetName = "old-name", presetApplicationToken = "old-token",
    )

    @Test fun applyCopiesEveryFieldAndAllModelMemoryWithoutSharingCollections() {
        val repo = ConversationSubAgentPreferences(prefs())
        val preset = repo.addPreset("完整组")
        val input = fullConfig()
        repo.update(p(preset.id)) { input }
        val owner = c("one")
        val applied = repo.applyPreset(owner, preset.id) as ConversationSubAgentPreferences.WriteResult.Saved
        val expected = input.detached().copy(appliedPresetId = preset.id, appliedPresetName = preset.name,
            presetApplicationToken = applied.config.presetApplicationToken)
        assertEquals(expected, repo.snapshot(owner))
        assertEquals(applied.config, repo.snapshot(owner))
        assertEquals(applied.revision, repo.revision(owner).value)
        assertNotNull(applied.config.presetApplicationToken)
        assertNotEquals("old-token", applied.config.presetApplicationToken)
        UUID.fromString(applied.config.presetApplicationToken)

        (input.profiles[0].reasoningByModel as MutableMap<*, *>).clear()
        (input.profiles[0].gptSpeedByModel as MutableMap<*, *>).clear()
        (input.parallelLimits as MutableMap<*, *>).clear()
        (input.legacyParallelLimits as MutableMap<*, *>).clear()
        assertEquals(expected, repo.snapshot(owner))
        assertEquals(expected.copy(appliedPresetId = "old-source", appliedPresetName = "old-name",
            presetApplicationToken = "old-token"), repo.snapshot(p(preset.id)))
        val snapshot = repo.snapshot(owner)
        assertNotSame(snapshot.profiles, repo.snapshot(p(preset.id)).profiles)
        assertNotSame(snapshot.profiles[0].reasoningByModel, repo.snapshot(p(preset.id)).profiles[0].reasoningByModel)
        assertNotSame(snapshot.profiles[0].gptSpeedByModel, repo.snapshot(p(preset.id)).profiles[0].gptSpeedByModel)

        val second = c("two")
        repo.applyPreset(second, preset.id)
        repo.update(owner) { it.copy(profiles = emptyList(), parallelLimits = emptyMap()) }
        assertEquals(expected.copy(presetApplicationToken = repo.snapshot(second).presetApplicationToken), repo.snapshot(second))
        assertNotEquals(repo.snapshot(owner).poolKey(owner, model), repo.snapshot(second).poolKey(second, model))
    }

    @Test fun editsRenameAndDeleteNeverChangePreviouslyAppliedConversationOrDraft() {
        val repo = ConversationSubAgentPreferences(prefs())
        val preset = repo.addPreset("原名")
        repo.update(p(preset.id)) { fullConfig() }
        val owner = c("copied")
        val draft = repo.createDraft()
        repo.applyPreset(owner, preset.id)
        repo.applyPreset(draft, preset.id)
        val before = repo.snapshot(owner)
        val draftBefore = repo.snapshot(draft)
        val ownerRevision = repo.revision(owner).value
        repo.update(p(preset.id)) { it.copy(enabled = true, profiles = emptyList(), diagnosticsEnabled = false) }
        assertTrue(repo.renamePreset(preset.id, "新名"))
        assertEquals("新名", repo.presets().first { it.id == preset.id }.name)
        assertEquals(before, repo.snapshot(owner))
        assertEquals(draftBefore, repo.snapshot(draft))
        assertEquals(ownerRevision, repo.revision(owner).value)
        assertTrue(repo.removePreset(preset.id))
        assertFalse(repo.presetExists(preset.id))
        assertEquals(before, repo.snapshot(owner))
        assertEquals(draftBefore, repo.snapshot(draft))
        repo.validateRestoredPreferences() // Dangling provenance is intentionally valid.
        repo.bindDraft(draft, c("promoted"))
        repo.confirmBoundDraft(draft, c("promoted"))
        assertEquals(draftBefore, repo.snapshot(c("promoted")))
    }

    @Test fun switchingToEmptyPresetFullyReplacesOldConfigAndAlwaysRenewsToken() {
        val repo = ConversationSubAgentPreferences(prefs())
        val full = repo.addPreset("完整")
        val empty = repo.addPreset("空白")
        repo.update(p(full.id)) { fullConfig() }
        val owner = c("switch")
        repo.applyPreset(owner, full.id)
        val oldToken = repo.snapshot(owner).presetApplicationToken
        repo.applyPreset(owner, empty.id)
        val next = repo.snapshot(owner)
        assertEquals(ConversationSubAgentConfig(emptyList(), appliedPresetId = empty.id, appliedPresetName = empty.name,
            presetApplicationToken = next.presetApplicationToken), next)
        assertNotEquals(oldToken, next.presetApplicationToken)
        repo.applyPreset(owner, empty.id)
        assertNotEquals(next.presetApplicationToken, repo.snapshot(owner).presetApplicationToken)
        assertEquals(empty.config, repo.snapshot(p(empty.id)))
    }

    @Test fun runningStaleMissingAndPresetTargetsAreRejectedWithoutAnyWrites() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs) { it != c("running") }
        val preset = repo.addPreset("组")
        repo.createConversation(c("running"))
        repo.createConversation(c("stale"))
        val before = prefs.all.toMap()
        val revision = repo.revision.value
        var staleChecked = false
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, repo.applyPreset(c("running"), preset.id))
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, repo.applyPreset(c("stale"), preset.id) {
            staleChecked = true
            false
        })
        assertTrue(staleChecked)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, repo.applyPreset(p(preset.id), preset.id))
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, repo.applyPreset(c("missing"), "absent"))
        assertEquals(before, prefs.all)
        assertEquals(revision, repo.revision.value)
        prefs.edit().remove(payloadKey(preset.id)).commit()
        val broken = prefs.all.toMap()
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, repo.applyPreset(c("missing"), preset.id))
        assertFalse(repo.presetExists(preset.id))
        assertEquals(broken, prefs.all)
    }

    @Test fun presetEditorIgnoresConversationGateButStillRequiresLiveDirectory() {
        val repo = ConversationSubAgentPreferences(prefs()) { false }
        val preset = repo.addPreset("独立编辑")
        assertTrue(repo.update(p(preset.id)) { it.copy(diagnosticsEnabled = true) }
            is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(repo.snapshot(p(preset.id)).diagnosticsEnabled)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, repo.applyPreset(c("blocked"), preset.id))
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, repo.update(p(preset.id)) {
            repo.removePreset(preset.id)
            it
        })
        assertFalse(repo.presetExists(preset.id))
    }

    @Test fun applyNeverSilentlyOverwritesCorruptExistingTarget() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val preset = repo.addPreset("有效")
        val owner = c("corrupt")
        val key = ConversationSubAgentPreferences.OWNER_PREFIX + "c_" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(owner.value.toByteArray(Charsets.UTF_8))
        listOf("{", JSONObject(repo.export(p(preset.id))).put("enabled", "bad type").toString()).forEach { raw ->
            prefs.edit().putString(key, raw).commit()
            val before = prefs.all.toMap()
            val version = repo.revision(owner).value
            assertThrows(Exception::class.java) { repo.applyPreset(owner, preset.id) }
            assertEquals(before, prefs.all)
            assertEquals(version, repo.revision(owner).value)
        }
    }

    @Test fun deletedPresetCannotBeRevivedByEditingImportingOrSeedFallback() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val preset = repo.addPreset("删除")
        val archive = repo.export(p(preset.id))
        assertTrue(repo.delete(p(preset.id))) // Generic delete must remove the directory entry as well.
        val before = prefs.all.toMap()
        var callbackCalled = false
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, repo.update(p(preset.id)) {
            callbackCalled = true
            fullConfig()
        })
        assertFalse(callbackCalled)
        assertFalse(repo.removePreset(preset.id))
        assertFalse(repo.renamePreset(preset.id, "复活"))
        assertThrows(IllegalArgumentException::class.java) { repo.snapshot(p(preset.id)) }
        assertThrows(IllegalArgumentException::class.java) { repo.previewSnapshot(p(preset.id)) }
        assertThrows(IllegalArgumentException::class.java) { repo.importOwner(p(preset.id), archive, overwrite = true) }
        assertEquals(before, prefs.all)
        repo.validateRestoredPreferences()
        assertThrows(IllegalStateException::class.java) { preset.config.poolKey(p(preset.id), model) }
        assertThrows(IllegalArgumentException::class.java) { repo.createDraft(p(preset.id)) }
        assertThrows(IllegalArgumentException::class.java) { repo.createConversation(c("no-copy"), p(preset.id)) }
    }

    @Test fun missingDirectoryDoesNotImportFrozenSeedAndListingIsReadOnly() = runBlocking {
        val prefs = prefs(); val repo = ConversationSubAgentPreferences(prefs)
        repo.update(c("existing")) { it.copy(profiles = listOf(SubAgentProfile("legacy", "Frozen"))) }
        val existing = repo.export(c("existing"))
        prefs.edit().putString(ConversationSubAgentPreferences.SEED_KEY, existing).commit()
        val beforeProbe = prefs.all.toMap()
        assertFalse(repo.presetExists(SubAgentPresetCatalog.DEFAULT_ID))
        assertFalse(repo.removePreset("missing"))
        assertTrue(repo.presets().isEmpty()); assertTrue(repo.presetsFlow().first().isEmpty())
        assertEquals(beforeProbe, prefs.all)
        assertEquals(existing, repo.export(c("existing")))
        val explicit = repo.addPreset("Explicit group")
        assertTrue(repo.removePreset(explicit.id))
        val empty = prefs.all.toMap()
        assertTrue(repo.presets().isEmpty()); assertTrue(ConversationSubAgentPreferences(prefs).presets().isEmpty())
        assertEquals(empty, prefs.all)
        val fresh = repo.addPreset("独立空组")
        assertEquals(ConversationSubAgentConfig(emptyList()), fresh.config)
        assertEquals(listOf(fresh), repo.presets())
    }

    @Test fun absentCatalogProbeIsReadOnlyAndOldConfigArchivesNeedNoProvenanceFields() {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        assertFalse(repo.presetExists("anything"))
        assertFalse(repo.removePreset("anything"))
        assertTrue(prefs.all.isEmpty())
        val old = JSONObject().put("version", 1).put("enabled", false).put("diagnostics_enabled", true)
            .put("agents", JSONArray()).put("parallel_limits", JSONArray()).toString()
        repo.validateArchive(old)
        assertTrue(prefs.all.isEmpty())
        assertTrue(repo.importOwner(c("old"), old))
        assertNull(repo.snapshot(c("old")).appliedPresetId)
        assertNull(repo.snapshot(c("old")).appliedPresetName)
        assertNull(repo.snapshot(c("old")).presetApplicationToken)
        val existing = repo.export(c("old"))
        assertTrue(repo.presets().isEmpty())
        assertEquals(existing, repo.export(c("old")))
    }

    @Test fun refreshAfterRestoreInvalidatesPresetAndOwnerRevisionsAndRefreshesCatalogFlow() = runBlocking {
        val prefs = prefs()
        val repo = ConversationSubAgentPreferences(prefs)
        val preset = repo.addPreset("观察")
        val owner = c("restored")
        repo.applyPreset(owner, preset.id)
        val presetRevision = repo.revision(p(preset.id)).value
        val ownerRevision = repo.revision(owner).value
        prefs.edit().putString(SubAgentPresetCatalog.KEY, SubAgentPresetCatalog.encode(emptyList()))
            .remove(payloadKey(preset.id)).remove(payloadKey(SubAgentPresetCatalog.DEFAULT_ID)).commit()
        repo.refreshAfterRestore()
        assertTrue(repo.presetsFlow().first().isEmpty())
        assertTrue(repo.revision(p(preset.id)).value > presetRevision)
        assertTrue(repo.revision(owner).value > ownerRevision)
        assertEquals(preset.id, repo.snapshot(owner).appliedPresetId)
    }
}
