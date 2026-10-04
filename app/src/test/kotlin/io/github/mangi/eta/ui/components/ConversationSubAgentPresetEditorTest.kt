package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
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
class ConversationSubAgentPresetEditorTest {
    private fun repository() = ConversationSubAgentPreferences(RuntimeEnvironment.getApplication()
        .getSharedPreferences("preset-editor-${UUID.randomUUID()}", Context.MODE_PRIVATE))

    @Test fun applyCopiesAndSessionEditsNeverWriteBackToPreset() {
        val repo = repository()
        val preset = repo.addPreset("Team")
        val worker = SubAgentProfile("same-id", "Worker")
        val presetOwner = SubAgentConfigKey.Preset(preset.id)
        repo.update(presetOwner) { it.copy(profiles = listOf(worker), enabled = false) }
        val owner = repo.createDraft()
        val editor = ConversationSubAgentEditor(owner, repo) { true }
        assertTrue(editor.applyPreset(preset.id) is ConversationSubAgentPreferences.WriteResult.Saved)
        val applied = repo.snapshot(owner)
        assertEquals(preset.id, applied.appliedPresetId)
        assertEquals("Team", applied.appliedPresetName)
        assertNotNull(applied.presetApplicationToken)
        assertEquals(listOf(worker), applied.profiles)
        assertFalse(applied.enabled)
        assertTrue(editor.updateProfile(worker.id) { it.copy(name = "Session only") } is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals("Worker", repo.snapshot(presetOwner).profiles.single().name)
        repo.update(presetOwner) { it.copy(profiles = listOf(worker.copy(name = "Preset only"))) }
        assertEquals("Session only", repo.snapshot(owner).profiles.single().name)
    }

    @Test fun scopedCallbacksCannotEditAfterRootObservesAnotherApplicationWithSameIds() {
        val repo = repository()
        val preset = repo.addPreset("Team")
        val worker = SubAgentProfile("same-id", "Worker")
        repo.update(SubAgentConfigKey.Preset(preset.id)) { it.copy(profiles = listOf(worker)) }
        val owner = repo.createDraft()
        val root = ConversationSubAgentEditor(owner, repo) { true }
        root.applyPreset(preset.id)
        val firstToken = repo.snapshot(owner).presetApplicationToken
        val oldRows = root.scoped(firstToken)
        val retainedCallback = { oldRows.updateProfile(worker.id) { it.copy(name = "stale") } }
        root.applyPreset(preset.id) // same root instance has already observed the Saved config
        assertNotEquals(firstToken, repo.snapshot(owner).presetApplicationToken)
        val before = repo.export(owner)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, retainedCallback())
        assertEquals(before, repo.export(owner))
        val freshRows = root.scoped(repo.snapshot(owner).presetApplicationToken)
        assertTrue(freshRows.updateProfile(worker.id) { it.copy(name = "fresh") } is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(freshRows.setEnabled(false) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals("fresh", repo.snapshot(owner).profiles.single().name)
    }

    @Test fun oldObservedTokenRejectsExternalApplyButSameTokenFieldsStillMerge() {
        val repo = repository()
        val owner = repo.createDraft()
        val root = ConversationSubAgentEditor(owner, repo) { true }
        val preset = repo.addPreset("Team")
        assertTrue(repo.update(owner) { it.copy(diagnosticsEnabled = true) } is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(root.setEnabled(false) is ConversationSubAgentPreferences.WriteResult.Saved)
        assertTrue(repo.snapshot(owner).diagnosticsEnabled)
        repo.applyPreset(owner, preset.id)
        val before = repo.export(owner)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, root.setEnabled(true))
        assertEquals(before, repo.export(owner))
    }

    @Test fun runningLifecycleOwnerAndTicketGatesRejectApplyAndPreserveConfig() {
        val repo = repository()
        val preset = repo.addPreset("Team")
        val owner = repo.createDraft()
        var stopped = true
        val root = ConversationSubAgentEditor(owner, repo) { stopped }
        val popup = OwnerBoundPopup(owner)
        val ticket = popup.open()
        val before = repo.export(owner)
        stopped = false
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, root.applyPreset(preset.id))
        stopped = true
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, root.applyPreset(preset.id) { false })
        popup.dismiss()
        popup.open()
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected,
            root.applyPreset(preset.id) { popup.isCurrent(ticket, popup) })
        root.bindLifecycleState({ "owner unavailable" }) { false }
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, root.applyPreset(preset.id))
        assertEquals(before, repo.export(owner))
    }

    @Test fun retainedConversationCallbackCannotRetargetAfterOwnerSwitch() {
        val repo = repository()
        val ownerA = SubAgentConfigKey.Conversation("a")
        val ownerB = SubAgentConfigKey.Conversation("b")
        var currentOwner = ownerA
        val rootA = ConversationSubAgentEditor(ownerA, repo) { currentOwner == ownerA }
        val oldPanel = rootA.scoped(repo.snapshot(ownerA).presetApplicationToken) { currentOwner == ownerA }
        val retained = { oldPanel.setEnabled(false) }
        currentOwner = ownerB
        val beforeA = repo.export(ownerA)
        val beforeB = repo.export(ownerB)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, retained())
        assertEquals(beforeA, repo.export(ownerA))
        assertEquals(beforeB, repo.export(ownerB))
        oldPanel.dispose()
        currentOwner = ownerA
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, retained())
        assertEquals(beforeA, repo.export(ownerA))
    }

    @Test fun presetDetailDisposedEditorNeverRevivesOnReenteringSameGroup() {
        val repo = repository()
        val preset = repo.addPreset("Team")
        val owner = SubAgentConfigKey.Preset(preset.id)
        var selected = preset.id
        val old = ConversationSubAgentEditor(owner, repo) { selected == preset.id && repo.presetExists(preset.id) }
        old.dispose()
        selected = ""
        selected = preset.id
        val fresh = ConversationSubAgentEditor(owner, repo) { selected == preset.id && repo.presetExists(preset.id) }
        val before = repo.export(owner)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, old.add())
        assertEquals(before, repo.export(owner))
        assertTrue(fresh.add() is ConversationSubAgentPreferences.WriteResult.Saved)
    }

    @Test fun suspendedSpeedRejectsNewApplicationEvenWhenProfileIsExactlyEqual() = runBlocking {
        val repo = repository()
        val preset = repo.addPreset("Team")
        val worker = SubAgentProfile("same-id", "Worker", providerId = "p", modelId = "m")
        repo.update(SubAgentConfigKey.Preset(preset.id)) { it.copy(profiles = listOf(worker)) }
        val owner = repo.createDraft()
        val lookup = CompletableDeferred<ProviderSetting?>()
        val root = ConversationSubAgentEditor(owner, repo, { lookup.await() }) { true }
        root.applyPreset(preset.id)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { root.cycleGptSpeed(worker.id, "p", "m") }
        assertFalse(pending.isCompleted)
        root.applyPreset(preset.id) // token changes but the profile data does not
        val before = repo.export(owner)
        val revision = repo.revision(owner).value
        lookup.complete(OpenAiCompatibleProviderSetting("p", "Provider", "https://example.invalid",
            models = listOf(Model("m", "gpt-5", "GPT"))))
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, pending.await())
        assertEquals(before, repo.export(owner))
        assertEquals(revision, repo.revision(owner).value)
    }
}
