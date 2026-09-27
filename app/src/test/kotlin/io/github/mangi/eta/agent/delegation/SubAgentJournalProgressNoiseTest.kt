package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class SubAgentJournalProgressNoiseTest {
    @Test fun whitespaceAndRepeatedTextCannotKeepBusinessProgressAlive() {
        var now = 100L
        val journal = SubAgentEventJournal(now = { now })
        fun delta(text: String, kind: AgentEvent.AssistantBlockKind = AgentEvent.AssistantBlockKind.TEXT) =
            AgentEvent.AssistantBlockDelta(round = 1, kind = kind, index = 0,
                deltaChars = text.length, delta = text)
        now = 200L
        assertFalse(journal.accept(delta("  \n \t ")))
        assertEquals(100L, journal.lastProgressMs)
        assertFalse(journal.accept(delta("private thinking", AgentEvent.AssistantBlockKind.THINKING)))
        assertEquals(100L, journal.lastProgressMs)
        assertTrue(journal.accept(delta("正在检查")))
        assertEquals(200L, journal.lastProgressMs)
        repeat(100) {
            now += 100
            assertFalse(journal.accept(delta("正在检查")))
            assertFalse(journal.accept(delta("  \n \t ")))
        }
        assertEquals(200L, journal.lastProgressMs)
        assertFalse(journal.page(0, 32).toString().contains("private thinking"))
        assertFalse(journal.page(0, 32).toString().contains("正在检查"))
    }

    @Test fun selfReportedProgressCannotResetClockButSuccessfulToolCan() {
        var now = 0L
        val journal = SubAgentEventJournal(now = { now })
        fun done(name: String) = AgentEvent.ToolFinished(round = 1, toolCallId = "a", name = name,
            resultSummary = "", imageCount = 0, imageBytes = 0, success = true)
        now = 1000L
        assertFalse(journal.accept(done("report_task_progress")))
        assertEquals(0L, journal.lastProgressMs)
        now = 2000L
        assertTrue(journal.accept(done("workspace_file")))
        assertEquals(2000L, journal.lastProgressMs)
    }
}
