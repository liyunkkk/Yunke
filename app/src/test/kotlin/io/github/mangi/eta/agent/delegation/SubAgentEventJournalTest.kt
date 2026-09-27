package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class SubAgentEventJournalTest {
    private fun delta(text: String, kind: AgentEvent.AssistantBlockKind = AgentEvent.AssistantBlockKind.TEXT) =
        AgentEvent.AssistantBlockDelta(round = 1, kind = kind, index = 0, deltaChars = text.length, delta = text)

    @Test fun heartbeatDoesNotExtendEffectiveProgress() {
        var now = 0L
        val journal = SubAgentEventJournal(3) { now }
        journal.mark("started", progress = true)
        now = 360_000
        journal.mark("heartbeat")
        assertEquals(0, journal.lastProgressMs)
        assertEquals(360_000, journal.lastHeartbeatMs)
        assertEquals(0, journal.page(0, 10).getLong("last_progress_ms"))
    }

    @Test fun overflowReportsMissingEventsAndNextCursor() {
        var now = 0L
        val journal = SubAgentEventJournal(2) { now++ }
        journal.mark("queued")
        journal.mark("started")
        journal.mark("tool_started", "private_tool", data = true)
        val page = journal.page(0, 50)
        assertTrue(page.getBoolean("truncated"))
        assertEquals(2, page.getLong("oldest_seq"))
        assertEquals(3, page.getLong("next_seq"))
        assertEquals("other", page.getJSONArray("events").getJSONObject(1).getString("tool"))
        val empty = journal.page(3, 50)
        assertEquals(3, empty.getLong("next_seq"))
        assertFalse(empty.getBoolean("truncated"))
    }

    @Test fun waitingCursorWakesForNewEvent() {
        val journal = SubAgentEventJournal()
        val worker = Thread { Thread.sleep(30); journal.mark("provider_response", data = true) }
        worker.start()
        val page = journal.awaitPage(0, 5, 1000)
        worker.join(1000)
        assertEquals(1, page.getJSONArray("events").length())
    }

    @Test fun sameBodyProgressesRegardlessOfDeltaPartition() {
        val body = "中文短词持续输出业务内容"
        for (width in listOf(1, 2, body.length)) {
            var now = 0L
            val journal = SubAgentEventJournal { now }
            var progressCount = 0
            for (part in body.chunked(width)) {
                now++
                if (journal.accept(delta(part))) progressCount++
            }
            assertTrue("width=$width", progressCount > 0)
            assertEquals("width=$width", now, journal.lastProgressMs)
            assertEquals("width=$width", now, journal.lastDataMs)
            assertFalse("width=$width", journal.page(0, 32).toString().contains(body))
        }
    }

    @Test fun shortTokensAccumulateButNoiseAndThinkingOnlyUpdateDataClock() {
        var now = 0L
        val journal = SubAgentEventJournal { now }
        now = 1
        assertFalse(journal.accept(delta("中")))
        assertEquals(0, journal.lastProgressMs)
        assertEquals(1, journal.lastDataMs)
        now = 2
        assertFalse(journal.accept(delta("文短")))
        now = 3
        assertTrue(journal.accept(delta("词")))
        assertEquals(3, journal.lastProgressMs)
        now = 4
        assertFalse(journal.accept(delta("****")))
        now = 5
        assertFalse(journal.accept(delta("   \n !?!  ")))
        now = 6
        assertFalse(journal.accept(delta("秘密推理正文", AgentEvent.AssistantBlockKind.THINKING)))
        assertEquals(3, journal.lastProgressMs)
        assertEquals(6, journal.lastDataMs)
        val page = journal.page(0, 32).toString()
        assertFalse(page.contains("秘密推理正文"))
        assertFalse(page.contains("中文短词"))
        assertEquals(1, journal.page(0, 32).getJSONArray("events").length())
    }

    @Test fun replayedTextDoesNotAdvanceProgressButDistinctWindowsBeyond32Do() {
        var now = 0L
        val journal = SubAgentEventJournal { now }
        for (i in 0 until 40) {
            now++
            assertTrue("window $i", journal.accept(delta("汉%03d".format(i))))
            assertEquals(now, journal.lastProgressMs)
        }
        now++
        assertFalse(journal.accept(delta("汉039")))
        assertEquals(40, journal.lastProgressMs)
        assertEquals(41, journal.lastDataMs)
        now++
        assertTrue(journal.accept(delta("汉000"))) // evicted, not a lifetime rejection
        assertEquals(42, journal.lastProgressMs)
    }

    @Test fun repeatedCompleteDeltaDoesNotShiftIncompleteWindow() {
        val journal = SubAgentEventJournal()
        assertTrue(journal.accept(delta("你好世界再")))
        assertFalse(journal.accept(delta("你好世界再")))
        assertTrue(journal.accept(delta("续写新句")))
        assertFalse(journal.accept(delta("续写新句")))
    }
}
