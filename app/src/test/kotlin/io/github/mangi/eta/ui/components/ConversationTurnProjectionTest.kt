package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTurnProjectionTest {
    @Test fun toolGroupsAndHiddenResumeDoNotMisalignTargets() {
        val entries = listOf(
            UserMessageUi("user-1", "first"),
            ThinkingMessageUi("thinking", "reasoning", false),
            ToolActivityMessageUi("tool-1", "search", ToolActivityStatusUi.Success, "args"),
            ToolActivityMessageUi("tool-2", "search", ToolActivityStatusUi.Success, "args"),
            UserMessageUi("user-1-supplement-resume", "resume"),
            AgentMessageUi("answer-1", "answer"),
            UserMessageUi("user-1-supplement-1", "extra instruction"),
            AgentMessageUi("answer-1b", "continuation"),
            UserMessageUi("user-2", "second"),
            AgentMessageUi("answer-2", "answer"),
        ).toTimelineEntries()
        assertEquals(7, entries.size)
        assertEquals(listOf(0, 3, 5), entries.userMessageIndices())
        assertEquals(3, conversationUserMessageTarget(entries.userMessageIndices(), 2, entries.size,
            ConversationNavigationDirection.Down, false))
    }
    @Test fun upwardJumpIncludesSupplementsAndNearestUserMessage() {
        val entries = listOf(
            UserMessageUi("user-1", "first"),
            AgentMessageUi("answer-1", "long answer"),
            UserMessageUi("user-1-supplement-1", "extra instruction"),
            AgentMessageUi("answer-1b", "continuation"),
            UserMessageUi("user-1-supplement-2", "another instruction"),
            AgentMessageUi("answer-1c", "final answer"),
            UserMessageUi("user-2", "second"),
            AgentMessageUi("answer-2", "answer"),
        ).toTimelineEntries()
        assertEquals(listOf(0, 2, 4, 6), entries.userMessageIndices())
        assertEquals(4, conversationUserMessageTarget(entries.userMessageIndices(), 6,
            entries.size, ConversationNavigationDirection.Up, false))
        assertEquals(6, conversationUserMessageTarget(entries.userMessageIndices(), 7,
            entries.size, ConversationNavigationDirection.Up, false))
        assertEquals(2, conversationUserMessageTarget(entries.userMessageIndices(), 3,
            entries.size, ConversationNavigationDirection.Up, false))
    }

    @Test fun orphanAnswerAndEmptyHistoryHaveNoInventedUserMessages() {
        assertEquals(emptyList<Int>(), listOf(AgentMessageUi("a", "answer")).toTimelineEntries().userMessageIndices())
        assertEquals(emptyList<Int>(), emptyList<AgentTimelineEntry>().userMessageIndices())
    }
}
