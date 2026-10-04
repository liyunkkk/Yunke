package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.delegation.*
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SubAgentModelDefaultsEditorTest {
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("model-defaults-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val provider = OpenAiCompatibleProviderSetting("p", "Provider", "https://example.invalid", models = listOf(
        Model("selection", "gpt-5", "GPT"), Model("second", "gpt-6", "Second")))
    private val pool = SubAgentParallelModel("p", "gpt-5")
    private val selection = ModelFeatureSelection(true, "p", "selection")

    @Test fun cancelAndModelSwitchesAreReadOnlyAndIdentityIsNeverRestored() = runBlocking {
        val storage = prefs(); val repo = ConversationSubAgentPreferences(storage); val owner = repo.createDraft()
        val editor = ConversationSubAgentEditor(owner, repo, { provider }) { true }
        val confirmed = editor.changeProfileModel(editor.newProfileDraft(), selection).copy(role = "review", enabled = false,
            reasoning = ReasoningEffort.HIGH, gptSpeedByModel = mapOf("p\u0000selection" to GptSpeedMode.ULTRA_FAST))
        assertTrue(editor.commitProfileDraft(confirmed, null, null, SubAgentParallelLimitChange(pool, 0, 1))
            is ConversationSubAgentPreferences.WriteResult.Saved)
        val before = storage.all.toMap(); val revision = repo.revision.value
        val draft = editor.newProfileDraft()
        val restored = editor.changeProfileModel(draft, selection)
        assertEquals(draft.id, restored.id); assertEquals(draft.name, restored.name)
        assertEquals("review", restored.role); assertFalse(restored.enabled)
        assertEquals(ReasoningEffort.HIGH, restored.reasoning); assertEquals(GptSpeedMode.ULTRA_FAST, restored.gptSpeedForModel())
        assertEquals(0, editor.rememberedParallelLimit(restored))
        val unknown = editor.changeProfileModel(restored, selection.copy(providerId = "other"))
        assertNull(unknown.reasoning); assertTrue(unknown.gptSpeedByModel.isEmpty()); assertNull(editor.rememberedParallelLimit(unknown))
        assertEquals(before, storage.all); assertEquals(revision, repo.revision.value)
        assertEquals(editor.newProfileDraft().name, editor.newProfileDraft().name)
    }

    @Test fun readdAndQuickSwitchRestoreAllConfirmedSettingsIncludingUnlimitedPool() = runBlocking {
        val repo = ConversationSubAgentPreferences(prefs()); val owner = repo.createDraft()
        val editor = ConversationSubAgentEditor(owner, repo, { provider }) { true }
        val first = editor.changeProfileModel(editor.newProfileDraft(), selection).copy(tier = SubAgentTaskTier.COMPLEX,
            enabled = false, reasoning = ReasoningEffort.HIGH,
            gptSpeedByModel = mapOf("p\u0000selection" to GptSpeedMode.FAST))
        assertTrue(editor.commitProfileDraft(first, null, null, SubAgentParallelLimitChange(pool, 0, 1))
            is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(editor.saveModel(first.id, selection.copy(modelId = "second"), first) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(editor.saveModel(first.id, selection) is ConversationSubAgentPreferences.WriteResult.Saved)
        val switched = repo.snapshot(owner).profiles.single()
        assertEquals(first.tier, switched.tier); assertFalse(switched.enabled); assertEquals(first.reasoning, switched.reasoning)
        assertEquals(GptSpeedMode.FAST, switched.gptSpeedForModel()); assertEquals(0, repo.snapshot(owner).parallelLimit(pool))
        editor.remove(first.id)
        val draft = editor.changeProfileModel(editor.newProfileDraft(), selection)
        assertTrue(editor.commitProfileDraft(draft, null, null, SubAgentParallelLimitChange(pool, 0, 0))
            is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals(0, repo.modelDefaults(draft)?.parallelLimit)
    }

    @Test fun imageParametersAreStoredAlongsideRoleAndRestoredWithoutChangingSelection() = runBlocking {
        val imageProvider = provider.copy(models = listOf(Model("selection", "gpt-image-1", "Image", outputModalities = listOf("image"))))
        val repo = ConversationSubAgentPreferences(prefs()); val owner = repo.createDraft()
        val editor = ConversationSubAgentEditor(owner, repo, { imageProvider }) { true }
        val draft = editor.newProfileDraft().copy(role = "image_generation", providerId = "p", modelId = "selection",
            imageResolution = io.github.mangi.eta.agent.model.ImageResolutionTier.values.first(), reasoning = ReasoningEffort.OFF)
        assertTrue(editor.commitProfileDraft(draft, null, null, null) is ConversationSubAgentPreferences.WriteResult.Saved)
        val restored = editor.changeProfileModel(editor.newProfileDraft(), selection)
        assertEquals("image_generation", restored.role); assertEquals(draft.imageResolution, restored.imageResolution)
        assertEquals(selection.providerId, restored.providerId); assertEquals(selection.modelId, restored.modelId)
    }

    @Test fun staleApplicationProfileDuplicateAndPoolConflictsHaveZeroWrites() = runBlocking {
        val storage = prefs(); val repo = ConversationSubAgentPreferences(storage); val owner = repo.createDraft()
        var running = false
        val editor = ConversationSubAgentEditor(owner, repo, { provider }) { !running }
        val draft = editor.changeProfileModel(editor.newProfileDraft(), selection)
        editor.commitProfileDraft(draft, null, null, null)
        suspend fun rejected(profile: SubAgentProfile = draft, expected: SubAgentProfile? = null, token: String? = null,
            limit: SubAgentParallelLimitChange? = null, gate: () -> Boolean = { true }) {
            val before = storage.all.toMap(); val revision = repo.revision.value
            assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, editor.commitProfileDraft(profile, expected, token, limit, gate))
            assertEquals(before, storage.all); assertEquals(revision, repo.revision.value)
        }
        rejected() // repeated add
        editor.updateProfile(draft.id) { it.copy(name = "changed") }
        rejected(draft.copy(reasoning = ReasoningEffort.HIGH), draft)
        val latest = repo.snapshot(owner).profiles.single()
        rejected(latest, latest, limit = SubAgentParallelLimitChange(pool, 2, 99))
        rejected(latest, latest, limit = SubAgentParallelLimitChange(SubAgentParallelModel("p", "selection"), 2, 1))
        rejected(latest, latest, gate = { false })
        running = true; rejected(latest, latest); running = false
        val preset = repo.addPreset("Replacement"); editor.applyPreset(preset.id)
        rejected(latest, null)
    }

    @Test fun suspensionRechecksOwnerAndOriginalProfileAndDoesNotHoldRepositoryLock() = runBlocking {
        val storage = prefs(); val repo = ConversationSubAgentPreferences(storage); val owner = repo.createDraft()
        val original = SubAgentProfile("existing", "Existing", providerId = "p", modelId = "selection")
        repo.update(owner) { it.copy(profiles = listOf(original)) }
        val lookup = CompletableDeferred<ProviderSetting?>()
        val editor = ConversationSubAgentEditor(owner, repo, { lookup.await() }) { true }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            editor.commitProfileDraft(original.copy(reasoning = ReasoningEffort.HIGH), original, null, null)
        }
        assertFalse(pending.isCompleted)
        // Progress here while provider lookup is suspended proves the owner transaction has not begun.
        repo.update(owner) { it.copy(profiles = listOf(original.copy(enabled = false))) }
        val before = storage.all.toMap(); lookup.complete(provider)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, pending.await())
        assertEquals(before, storage.all)
        editor.dispose()
        assertTrue(editor.isDisposed)
        assertFalse(editor.enabled)
    }

    @Test fun presetApplicationAndSwitchesDoNotCollectAndPayloadRemainsIndependent() = runBlocking {
        val storage = prefs(); val repo = ConversationSubAgentPreferences(storage); val owner = repo.createDraft()
        val preset = repo.addPreset("Source"); val presetOwner = SubAgentConfigKey.Preset(preset.id)
        repo.update(presetOwner) { it.copy(profiles = listOf(SubAgentProfile("source", "Source", providerId = "p", modelId = "selection"))) }
        val payload = repo.export(presetOwner)
        val editor = ConversationSubAgentEditor(owner, repo, { provider }) { true }
        editor.applyPreset(preset.id); editor.setEnabled(true); editor.setDiagnosticsEnabled(true); repo.presets()
        assertFalse(storage.contains(SubAgentModelDefaults.KEY))
        val old = repo.snapshot(owner).profiles.single(); val token = repo.snapshot(owner).presetApplicationToken
        assertTrue(editor.commitProfileDraft(old.copy(reasoning = ReasoningEffort.LOW), old, token, null)
            is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals(payload, repo.export(presetOwner))
        assertEquals(ReasoningEffort.LOW, repo.modelDefaults(old)?.reasoning)
    }
}
