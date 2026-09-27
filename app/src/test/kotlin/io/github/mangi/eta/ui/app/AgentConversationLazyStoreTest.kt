package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.MessageSearchRoleLabels
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationLazyStoreTest {
    private lateinit var context: Context
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    private fun chat(id: String, count: Int = 260) = AgentChatHomeUiState(
        messages = List(count) { UserMessageUi("$id-$it", "payload-$id-$it Needle") },
        history = listOf(AgentModelClient.ConversationMessage("user", "full history $id")),
        input = "", isStreaming = false, thinkingEnabled = false,
        providerId = "p", modelId = "m", livePromptTokens = 1200,
    )

    private fun seed() = runBlocking {
        AgentConversationStore.save(context, "a", mapOf("a" to chat("a", 2), "b" to chat("b")),
            mapOf("a" to "A", "b" to "B"), mapOf("a" to 2L, "b" to 1L))
    }

    @Test fun selectedOnlyLoadAndMetadataSavePreserveUnloadedChildrenAndReceipt() = runBlocking {
        seed()
        val dao = EtaDatabase.get(context).conversationDao()
        val checkpoint = dao.contextCheckpoint("b")
        val original = dao.messagesForConversation("b")
        val lazy = AgentConversationStore.load(context, selectedOnly = true)
        assertTrue(lazy.conversationsById.getValue("a").conversationContentLoaded)
        assertFalse(lazy.conversationsById.getValue("b").conversationContentLoaded)
        assertTrue(lazy.conversationsById.getValue("b").history.isEmpty())
        assertTrue(lazy.conversationsById.getValue("b").messages.size <= 1)
        AgentConversationStore.save(context, "a", lazy.conversationsById,
            lazy.titles + ("b" to "renamed"), lazy.updatedAt + ("b" to 99L), pinnedIds = setOf("b"))
        assertEquals(original, dao.messagesForConversation("b"))
        assertEquals(checkpoint, dao.contextCheckpoint("b"))
        assertEquals(1L, dao.conversationMetadata("b")!!.createdAt)
        assertTrue(dao.conversationMetadata("b")!!.isPinned)
        val loaded = requireNotNull(AgentConversationStore.loadConversation(context, "b"))
        assertTrue(loaded.conversationContentLoaded)
        assertEquals(260, loaded.messages.size)
        assertEquals("full history b", loaded.history.single().content)
        assertEquals(1200, loaded.livePromptTokens)
    }

    @Test fun explicitEmptyLoadedContentAndUnloadedDeletionAreDifferent() = runBlocking {
        seed()
        val lazy = AgentConversationStore.load(context, selectedOnly = true)
        val emptyA = lazy.conversationsById.getValue("a").copy(messages = emptyList(), history = emptyList())
        AgentConversationStore.save(context, "a", lazy.conversationsById + ("a" to emptyA), lazy.titles, lazy.updatedAt)
        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals(0, dao.messageCount("a"))
        assertEquals(260, dao.messageCount("b"))
        AgentConversationStore.save(context, "a", mapOf("a" to emptyA), lazy.titles, lazy.updatedAt)
        assertNull(dao.conversationMetadata("b"))
        assertNull(dao.contextCheckpoint("b"))
        assertEquals(0, dao.messageCount("b"))
        AgentConversationStore.save(context, null, emptyMap(), emptyMap(), emptyMap())
        assertEquals(0, dao.conversationCount())
        assertNull(dao.state())
    }

    @Test fun failedLaterPageRollsBackMetadataContentAndSelection() = runBlocking {
        seed()
        val db = EtaDatabase.get(context)
        val dao = db.conversationDao()
        val before = dao.messagesForConversation("a")
        val checkpoint = dao.contextCheckpoint("a")
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_late_message BEFORE INSERT ON conversation_messages
            WHEN NEW.id = 'broken-129' BEGIN SELECT RAISE(ABORT, 'injected page failure'); END
        """.trimIndent())
        val result = runCatching {
            AgentConversationStore.save(context, "c", mapOf("a" to chat("broken"), "c" to chat("c", 1)),
                mapOf("a" to "changed", "c" to "C"), mapOf("a" to 3L, "c" to 4L))
        }
        assertTrue(result.isFailure)
        assertEquals(before, dao.messagesForConversation("a"))
        assertEquals(checkpoint, dao.contextCheckpoint("a"))
        assertEquals("A", dao.conversationMetadata("a")!!.title)
        assertEquals(260, dao.messageCount("b"))
        assertNull(dao.conversationMetadata("c"))
        assertEquals("a", dao.state()!!.selectedConversationId)
    }

    @Test fun storedSearchCoversAllPagesWithoutHydratingCheckpoint() {
        seed()
        val lazy = AgentConversationStore.load(context, selectedOnly = true)
        val hits = AgentConversationStore.searchStoredConversation(context, "b", "B", 1L, "needle", "untitled",
            MessageSearchRoleLabels("u", "a", "t", "tool"))
        assertEquals(260, hits.size)
        assertEquals("b-0", hits.first().messageId)
        assertEquals("b-259", hits.last().messageId)
        assertFalse(lazy.conversationsById.getValue("b").conversationContentLoaded)
        assertTrue(lazy.conversationsById.getValue("b").history.isEmpty())
    }

    @Test fun unloadedPreviewDropsHistoryAndLargeTextButCanNeverBeSavedAsLoadedContent() {
        val state = chat("x").copy(messages = listOf(AgentMessageUi("long", "x".repeat(50_000))))
        val preview = AgentConversationStore.unloadedPreview(state)
        assertFalse(preview.conversationContentLoaded)
        assertTrue(preview.history.isEmpty())
        assertEquals(2048, (preview.messages.single() as AgentMessageUi).content.length)
        val result = runCatching { runBlocking {
            AgentConversationStore.save(context, "x", mapOf("x" to preview), mapOf("x" to "X"), mapOf("x" to 1L))
        } }
        assertTrue(result.isFailure)
    }
}
