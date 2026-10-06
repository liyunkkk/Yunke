package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class AgentRunningBranchSnapshotTest {
    private val user = ConversationMessage("user", "task", turnId = "run")
    private val partial = AgentMessageUi("assistant-run-1-0", "click text", isStreaming = true)
    private fun state(answer: AgentMessageUi = partial) = AgentChatHomeUiState(
        messages = listOf(UserMessageUi("user-run", "task"), answer),
        history = emptyList(), input = "", isStreaming = true, thinkingEnabled = false)

    @Test fun currentTextCopiesExactlyTheClickWithoutSourceMutationOrTools() {
        val source = state()
        val prepared = requireNotNull(AgentRunningBranchSnapshot.prepare(source, partial.id, "run", 1, listOf(user), emptyMap()))
        val branch = requireNotNull(AgentConversationRevisionReducer.branchPrefix(prepared, partial.id))
        assertEquals(listOf(user, ConversationMessage("assistant", "click text", turnId = "run")), branch.history)
        assertTrue(branch.history.last().toolCallsJson.isEmpty())
        assertTrue(branch.history.last().reasoningContent.isEmpty())
        assertTrue(source.history.isEmpty())
        assertTrue((source.messages.last() as AgentMessageUi).isStreaming)
    }

    @Test fun earlierRoundToolsArePreservedFromModelNotReconstructedFromUi() {
        val tools = """[{"id":"call","type":"function","function":{"name":"tool","arguments":"{}"}}]"""
        val before = listOf(user, ConversationMessage("assistant", "looking", toolCallsJson = tools, turnId = "run"),
            ConversationMessage("tool", "full original result", toolCallId = "call", turnId = "run"))
        val second = AgentMessageUi("assistant-run-2-0", "result", isStreaming = true)
        val source = state(second).copy(messages = listOf(UserMessageUi("user-run", "task"),
            AgentMessageUi("assistant-run-1-0", "looking"), second))
        val prepared = requireNotNull(AgentRunningBranchSnapshot.prepare(source, second.id, "run", 2, before, emptyMap()))
        val branch = requireNotNull(AgentConversationRevisionReducer.branchPrefix(prepared, second.id))
        assertEquals(before + ConversationMessage("assistant", "result", turnId = "run"), branch.history)
    }

    @Test fun resumedSameBlockReplacesExactConsumedTextAndHiddenContinueOnly() {
        val before = listOf(user, ConversationMessage("assistant", "click", turnId = "run"),
            ConversationMessage("user", AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT, turnId = "run"))
        val prepared = requireNotNull(AgentRunningBranchSnapshot.prepare(state(), partial.id, "run", 1, before,
            mapOf(partial.id to "click")))
        assertEquals(listOf(user, ConversationMessage("assistant", "click text", turnId = "run")), prepared.history)
        assertNotNull(AgentConversationRevisionReducer.branchPrefix(prepared, partial.id))
        assertNull(AgentRunningBranchSnapshot.prepare(state(), partial.id, "run", 1, before,
            mapOf(partial.id to "different")))
    }

    @Test fun queuedSupplementAndUnpairedToolsDoNotProduceInventedHistory() {
        val queued = UserMessageUi("user-run-supplement-1", "later")
        val source = state().copy(messages = listOf(UserMessageUi("user-run", "task"), queued, partial))
        assertNull(AgentRunningBranchSnapshot.prepare(source, partial.id, "run", 1, listOf(user), emptyMap()))
        val tools = """[{"id":"missing","type":"function","function":{"name":"tool","arguments":"{}"}}]"""
        val prepared = requireNotNull(AgentRunningBranchSnapshot.prepare(state(), partial.id, "run", 1,
            listOf(user, ConversationMessage("assistant", toolCallsJson = tools, turnId = "run")), emptyMap()))
        assertNull(AgentConversationRevisionReducer.branchPrefix(prepared, partial.id))
    }

    @Test fun pausedMultiTextCanBranchAtAnEarlierBlockWithoutIncludingLaterText() {
        val first = partial.copy(content = "A")
        val second = AgentMessageUi("assistant-run-1-1", "B+", isStreaming = true)
        val source = state(first).copy(messages = listOf(UserMessageUi("user-run", "task"), first, second))
        val before = listOf(user, ConversationMessage("assistant", "A", turnId = "run"),
            ConversationMessage("assistant", "B", turnId = "run"),
            ConversationMessage("user", AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT, turnId = "run"))
        val baseline = mapOf(first.id to "A", second.id to "B")
        val prepared = requireNotNull(AgentRunningBranchSnapshot.prepare(source, first.id, "run", 1, before, baseline))
        val branch = requireNotNull(AgentConversationRevisionReducer.branchPrefix(prepared, first.id))
        assertEquals(listOf(user, ConversationMessage("assistant", "A", turnId = "run")), branch.history)
        val full = requireNotNull(AgentRunningBranchSnapshot.prepare(source, second.id, "run", 1, before, baseline))
        assertEquals(listOf(user, ConversationMessage("assistant", "A", turnId = "run"),
            ConversationMessage("assistant", "B+", turnId = "run")), full.history)
    }

    @Test fun executionRunCanDifferFromTheStableLogicalTurnOnRegeneration() {
        val source = state().copy(messages = listOf(UserMessageUi("user-logical", "task"), partial))
        val before = listOf(user.copy(turnId = "logical"))
        val prepared = requireNotNull(AgentRunningBranchSnapshot.prepare(source, partial.id, "run", 1, before, emptyMap()))
        assertEquals(before + ConversationMessage("assistant", partial.content, turnId = "logical"), prepared.history)
    }

    @Test fun currentMediaRestoresFilesWithoutReplacingTheWorkerPrompt() {
        val path = "/cache/eta-chat-images/conv-source/image.jpg"
        val visible = UserMessageUi("user-run", "task", images = listOf(path), imageSources = listOf(path))
        val stored = ConversationMessage("user", contentJson =
            """[{"type":"text","text":"task"},{"type":"image_file","path":"$path","mime":"image/jpeg","name":"image"}]""",
            turnId = "run")
        val model = ConversationMessage("user", contentJson =
            """[{"type":"text","text":"task"},{"type":"text","text":"[图片观察已在当前回合使用，未写入持久会话]"}]""",
            turnId = "run")
        val source = state().copy(messages = listOf(visible, partial), history = listOf(stored))
        val prepared = requireNotNull(AgentRunningBranchSnapshot.prepare(source, partial.id, "run", 1, listOf(model), emptyMap()))
        assertEquals(listOf(path), io.github.mangi.eta.agent.model.AgentConversationCodec.persistedImageSources(prepared.history.first()))
        val parts = org.json.JSONArray(prepared.history.first().contentJson)
        assertEquals("task", parts.getJSONObject(0).getString("text"))
        assertEquals("image_file", parts.getJSONObject(1).getString("type"))
        assertNotNull(AgentConversationRevisionReducer.branchPrefix(prepared, partial.id))
    }

    @Test fun anotherLogicalTurnAtWorkerTailRejectsCurrentText() {
        assertNull(AgentRunningBranchSnapshot.prepare(state(), partial.id, "run", 1,
            listOf(user, ConversationMessage("user", "later", turnId = "other")), emptyMap()))
    }

    @Test fun changedIdentityOrRemovedUserFailsClosed() {
        assertNull(AgentRunningBranchSnapshot.prepare(state(), partial.id, "run", 1,
            listOf(ConversationMessage("user", "wrong", turnId = "other")), emptyMap()))
        assertNull(AgentRunningBranchSnapshot.prepare(state().copy(messages = listOf(partial)), partial.id,
            "run", 1, listOf(user), emptyMap()))
    }
}
