package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTerminalOwnershipRegressionTest {
    @Test
    fun numericSuffixRunOwnsItsOwnRoundAndNotice() {
        val shortUser = UserMessageUi("user-build", "short")
        val shortText = AgentMessageUi("assistant-build-1-0", "short body")
        val shortNotice = SystemNoticeMessageUi("interrupted-build", SystemNoticeCode.Stopped, "short detail")
        val shortWork = ThinkingMessageUi("build-thinking-1-0", "late thinking", false)
        val longUser = UserMessageUi("user-build-7", "long")
        val longText = AgentMessageUi("assistant-build-7-1-0", "long body")
        val longNotice = SystemNoticeMessageUi("assistant-build-7-1", SystemNoticeCode.RuntimeFailed, "long detail")
        val input = listOf(shortUser, shortText, shortNotice, shortWork, longUser, longText, longNotice)
        val expected = listOf(shortUser, shortText, shortWork, shortNotice, longUser, longText, longNotice)

        assertEquals(expected, normalizeTerminalRunMessages("build", input))
        assertEquals(expected, input.withTerminalBodiesInOrder())
        assertEquals(expected, expected.withTerminalBodiesInOrder())
    }

    @Test
    fun latestTerminalStateWinsWithoutSwallowingRetryOrAnotherRun() {
        val user = UserMessageUi("user-build", "task")
        val original = AgentMessageUi("assistant-build-1-0", "partial", isStreaming = true)
        val firstNotice = SystemNoticeMessageUi("assistant-build-2-usage", SystemNoticeCode.Stopped, "stopped")
        val retry = SystemNoticeMessageUi("assistant-build-3-usage", SystemNoticeCode.ModelRetry, "retry detail")
        val otherUser = UserMessageUi("user-build-7", "other task")
        val otherNotice = SystemNoticeMessageUi("assistant-build-7-1", SystemNoticeCode.RuntimeFailed, "other detail")
        val updated = original.copy(content = "complete", isStreaming = false)
        val updatedNotice = SystemNoticeMessageUi("interrupted-build", SystemNoticeCode.Interrupted, "latest detail")
        val input = listOf(user, original, firstNotice, retry, otherUser, otherNotice, updated, updatedNotice)
        val expected = listOf(
            user, updated, firstNotice.copy(code = SystemNoticeCode.Interrupted, detail = "latest detail"),
            retry, otherUser, otherNotice,
        )

        assertEquals(expected, normalizeTerminalRunMessages("build", input))
        assertEquals(expected, input.withTerminalBodiesInOrder())
        assertEquals(expected, normalizeTerminalRunMessages("build", expected))
    }

    @Test
    fun unanchoredExplicitRunDoesNotClaimAmbiguousAssistantIds() {
        val notice = SystemNoticeMessageUi("assistant-build-2-usage", SystemNoticeCode.Stopped)
        val possibleOtherRun = AgentMessageUi("assistant-build-7-1", "unclaimed")
        val input = listOf(notice, possibleOtherRun)

        assertEquals(input, normalizeTerminalRunMessages("build", input))
        assertEquals(input, input.withTerminalBodiesInOrder())
    }
}
