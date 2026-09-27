package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConversationSubAgentEditorLifecycleTest {
    private fun repository() = ConversationSubAgentPreferences(
        RuntimeEnvironment.getApplication().getSharedPreferences("editor-${UUID.randomUUID()}", Context.MODE_PRIVATE))

    @Test fun existingEditorExposesLaterLifecycleFailureAndExplicitRecovery() {
        val repo = repository()
        val draft = repo.createDraft()
        val editor = ConversationSubAgentEditor(draft, repo) { true }
        var error: String? = null
        var recoveries = 0
        editor.bindLifecycleState({ error }) { recoveries++; error = null; true }
        assertTrue(editor.enabled)
        val before = repo.export(draft)
        error = "draft write failed"
        assertTrue(editor.state is SubAgentEditorState.Error)
        assertFalse(editor.enabled)
        assertEquals(ConversationSubAgentPreferences.WriteResult.Rejected, editor.setEnabled(true))
        assertEquals(before, repo.export(draft))
        editor.retry()
        assertEquals(1, recoveries)
        assertEquals(SubAgentEditorState.Loading, editor.state)
        assertEquals(before, repo.export(draft))
    }

    @Test fun failedLifecycleRecoveryCannotUnlockOrReplaceTheOwner() {
        val repo = repository()
        val draft = repo.createDraft()
        val editor = ConversationSubAgentEditor(draft, repo) { true }
        editor.bindLifecycleState({ "still blocked" }) { false }
        editor.retry()
        assertTrue(editor.state is SubAgentEditorState.Error)
        assertFalse(editor.enabled)
        assertEquals(draft, editor.owner)
    }

    @Test fun strictDraftLookupNeverMaterializesASeedOwnerForMissingPointer() {
        val repo = repository()
        val missing = SubAgentConfigKey.Draft("missing")
        assertNull(repo.existingDraftOrNull(missing))
        assertNull(repo.existingDraftOrNull(missing))
        val actual = repo.createDraft()
        assertNotNull(repo.existingDraftOrNull(actual))
    }
}
