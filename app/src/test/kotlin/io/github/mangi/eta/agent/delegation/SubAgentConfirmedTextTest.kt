package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class SubAgentConfirmedTextTest {
    private fun delta(text: String) = AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, text.length, text)
    private fun end(text: String? = null) = AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TEXT, 0, contentChars = text?.length ?: 0, replacementContent = text)

    @Test fun truncatedDeltaAndReplacementCannotRetainHalfOfAnEmoji() {
        for (replace in listOf(false, true)) {
            val confirmed = SubAgentConfirmedText(limit = 4)
            if (!replace) confirmed.accept(delta("abc🙂suffix"))
            confirmed.accept(end(if (replace) "abc🙂suffix" else null))
            assertEquals("abc", confirmed.value())
            assertTrue(confirmed.truncated)
        }
    }

    @Test fun streamingSurrogateHalvesAreJoinedUntilTheTextBlockIsComplete() {
        val confirmed = SubAgentConfirmedText(limit = 4)
        confirmed.accept(delta("ab\uD83D"))
        assertEquals("", confirmed.value())
        confirmed.accept(delta("\uDE42"))
        confirmed.accept(end())
        assertEquals("ab🙂", confirmed.value())
        assertFalse(confirmed.truncated)
    }
}
