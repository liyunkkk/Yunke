package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDraftsTest {
    private fun preferences() = RuntimeEnvironment.getApplication().getSharedPreferences(
        "draft-test-${UUID.randomUUID()}", Context.MODE_PRIVATE,
    )

    @Test fun destinationsAndConversationsKeepTheirOwnEditorAndSelection() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val drafts = ConversationDrafts(preferences(), scope)
            val a = drafts.field("a")
            a.setTextAndPlaceCursorAtEnd("A 草稿")
            val b = drafts.field("b")
            b.setTextAndPlaceCursorAtEnd("B 草稿")
            assertSame(a, drafts.field("a"))
            assertSame(b, drafts.field("b"))
            assertEquals("A 草稿", drafts.field("a").text.toString())
            assertEquals("B 草稿", drafts.field("b").text.toString())
            assertEquals(4, drafts.field("a").selection.end)
        } finally { scope.cancel() }
    }

    @Test fun typedTextRestoresFromStorageAndDeletionDoesNotReappear() = runBlocking {
        val prefs = preferences()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val drafts = ConversationDrafts(prefs, scope)
            drafts.field("a").setTextAndPlaceCursorAtEnd("未发送\n第二行 😀")
            Snapshot.sendApplyNotifications()
            yield()
            val restored = ConversationDrafts(prefs, scope)
            assertEquals("未发送\n第二行 😀", restored.field("a").text.toString())
            val removed = drafts.field("b")
            drafts.remove("b")
            removed.setTextAndPlaceCursorAtEnd("late event")
            Snapshot.sendApplyNotifications()
            yield()
            assertEquals("", drafts.field("b").text.toString())
        } finally { scope.cancel() }
    }

    @Test fun sendingNewConversationClearsOnlyItsDraft() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val prefs = preferences()
            val drafts = ConversationDrafts(prefs, scope)
            drafts.replace("other", "keep")
            drafts.replace(null, "new message")
            drafts.promote("created")
            assertEquals("new message", drafts.field("created").text.toString())
            assertEquals("", drafts.field(null).text.toString())
            drafts.replace("created", "")
            assertEquals("keep", drafts.field("other").text.toString())
            assertEquals("", ConversationDrafts(prefs, scope).field("created").text.toString())
            drafts.clear()
            assertEquals("", drafts.field("other").text.toString())
        } finally { scope.cancel() }
    }
}
