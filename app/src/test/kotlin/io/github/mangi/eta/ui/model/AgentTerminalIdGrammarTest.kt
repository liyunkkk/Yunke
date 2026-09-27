package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentTerminalIdGrammarTest {
    @Test
    fun whitespaceOnlyOwnerIsNotNormalizedEvenWhenInferredFromAnExplicitNotice() {
        val input = listOf(
            UserMessageUi("user- ", "question"),
            SystemNoticeMessageUi("interrupted- ", SystemNoticeCode.Stopped),
            AgentMessageUi("assistant- -1-0", "body"),
        )
        assertSame(input, input.withTerminalBodiesInOrder())
        assertEquals(LegacyTerminalMessageOrderReference.orderAll(input), input.withTerminalBodiesInOrder())
    }

    @Test
    fun nestedToolMarkersKeepEarliestAnchorButLongestKnownOwner() {
        val input = listOf(
            UserMessageUi("user-root", "short"),
            SystemNoticeMessageUi("interrupted-root", SystemNoticeCode.Stopped),
            UserMessageUi("user-root-tool-1-call", "long"),
            SystemNoticeMessageUi("interrupted-root-tool-1-call", SystemNoticeCode.Stopped),
            ToolActivityMessageUi("root-tool-1-call-tool-2-tail", "read", ToolActivityStatusUi.Success, "args", resultSummary = "result"),
            ToolActivityMessageUi("root-tool-1-call", "read", ToolActivityStatusUi.Success, "short args"),
        )
        assertEquals(LegacyTerminalMessageOrderReference.orderAll(input), input.withTerminalBodiesInOrder())
        assertEquals(listOf(input[0], input[5], input[1], input[2], input[4], input[3]), input.withTerminalBodiesInOrder())
    }

    @Test
    fun suffixParserRetainsAsciiDigitsAndRegexLineTerminatorRules() {
        val suffixes = listOf("1", "1-0", "1-fallback", "1-result", "1-usage", "١", "1-", "1-tail", "1-\n", "1-\r", "1-\u0085", "1-\u2028", "1-\u2029")
        for (suffix in suffixes) {
            val input = listOf(
                SystemNoticeMessageUi("assistant-unanchored-2-usage", SystemNoticeCode.Stopped),
                AgentMessageUi("assistant-unanchored-1-0", "body"),
                ThinkingMessageUi("unanchored-thinking-$suffix", "thinking", false),
                ToolActivityMessageUi("unanchored-tool-$suffix", "read", ToolActivityStatusUi.Success, "args"),
            )
            assertEquals("Unanchored suffix $suffix", LegacyTerminalMessageOrderReference.orderAll(input), input.withTerminalBodiesInOrder())
            val anchored = listOf(UserMessageUi("user-unanchored", "anchor")) + input
            assertEquals("Anchored suffix $suffix", LegacyTerminalMessageOrderReference.orderAll(anchored), anchored.withTerminalBodiesInOrder())
        }
    }
}
