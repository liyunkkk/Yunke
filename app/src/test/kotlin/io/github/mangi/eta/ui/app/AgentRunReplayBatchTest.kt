package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.normalizeTerminalRunMessages
import io.github.mangi.eta.ui.model.terminalOrderLargeHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentRunReplayBatchTest {
    @Test
    fun streamingAssistantAppendUsesIncrementalNormalizationUntilStructureChanges() {
        val runId = "incremental"
        val user = UserMessageUi("user-$runId", "task")
        val first = AgentMessageUi("assistant-$runId", "a", isStreaming = true)
        val batch = AgentRunReplayBatch()

        assertEquals(listOf(user, first), batch.normalize(runId, listOf(user, first)))
        val second = first.copy(content = "ab")
        assertSame(second, batch.normalize(runId, listOf(user, second)).last())
        assertEquals(1, batch.incrementalFastPathHits)

        val terminal = first.copy(content = "ab", isStreaming = false)
        val notice = SystemNoticeMessageUi("notice-$runId", SystemNoticeCode.Stopped)
        batch.normalize(runId, listOf(user, notice, terminal))
        assertEquals("Terminal state and structure use the full path", 1, batch.incrementalFastPathHits)
    }

    @Test
    fun thousandMixedReplayEventsOn3200MessageHistoryNormalizeAndSummarizeOnce() {
        val events = buildList<AgentEvent> {
            repeat(100) { round ->
                addBlock(round, AgentEvent.AssistantBlockKind.THINKING)
                addBlock(round, AgentEvent.AssistantBlockKind.TEXT)
                add(AgentEvent.ToolStarted(round, "call-$round", "read", "args-$round"))
                add(AgentEvent.ToolFinished(round, "call-$round", "read", "result-$round", 0, 0, true))
            }
        }
        assertEquals(1_000, events.size)
        assertLargeReplay(events, expectedApplications = 1_000, expectedBodySize = 300)
    }

    @Test
    fun deviceSized256EventCheckpointOnLongHistoryAlsoOrdersOnlyAtTheBoundary() {
        val events = buildList<AgentEvent> {
            repeat(64) { round -> addBlock(round, AgentEvent.AssistantBlockKind.TEXT) }
        }
        assertEquals(256, events.size)
        assertLargeReplay(events, expectedApplications = 256, expectedBodySize = 64)
    }

    @Test
    fun replayPreservesEveryDeltaAndAllEventBoundaries() {
        val events = listOf(
            delta(1, 0, "a"), delta(1, 0, "b"),
            AgentEvent.UserSupplementReceived(1, "steer"),
            delta(1, 0, "c"), delta(1, 1, "d"), delta(2, 1, "e"),
            AgentEvent.ContextCompactionStarted(2),
            AgentEvent.RunFinished(2, 5),
        )
        val applied = mutableListOf<AgentEvent>()
        AgentRunReplayBatch().replay("run", events, {}, applied::add, {})
        assertEquals(events, applied)
    }

    @Test
    fun adjacentLateThinkingPrefixesKeepSerialDeduplicationSemantics() {
        val runId = "late-thinking"
        val events = listOf(
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 0),
            AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 3, "abc"),
            AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.THINKING, 0, contentChars = 3),
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TEXT, 0),
            delta(1, 0, "answer"),
            AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TEXT, 0, contentChars = 6),
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 1),
            AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 1, 1, "a"),
            AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 1, 2, "ab"),
        )
        val initial = listOf<AgentChatMessageUi>(UserMessageUi("user-$runId", "task"))
        val serialProjector = AgentRunMessageProjector { 1_000L }
        var serial = initial
        events.forEach { serial = project(serialProjector, runId, it, serial) }
        val replayProjector = AgentRunMessageProjector { 1_000L }
        var replayed = initial
        val batch = AgentRunReplayBatch()
        batch.replay(runId, events, reset = {}, apply = {
            replayed = batch.normalize(runId, project(replayProjector, runId, it, replayed))
        }, finish = { replayed = batch.normalize(runId, replayed) })
        assertEquals(serial, replayed)
        val thinkings = replayed.filterIsInstance<ThinkingMessageUi>()
        assertEquals(1, thinkings.size)
        assertEquals("abc", thinkings.single().content)
        assertFalse(thinkings.single().isStreaming)
        assertTrue(thinkings.single().collapsed)
    }

    @Test
    fun exceptionsClearDeferralAndDoNotFinishAnIncompleteReplay() {
        val batch = AgentRunReplayBatch()
        var finished = false
        try {
            batch.replay("run", listOf(delta(1, 0, "a")), {}, { error("failed event") }, { finished = true })
            fail("Must propagate the event failure")
        } catch (_: IllegalStateException) {
            assertFalse(batch.isActive)
            assertFalse(finished)
        }
        val user = UserMessageUi("user-run", "task")
        val notice = SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.Stopped)
        val text = AgentMessageUi("assistant-run-1-0", "late")
        assertEquals(listOf(user, text, notice), batch.normalize("run", listOf(user, notice, text)))
        batch.replay("next-run", emptyList(), {}, {}, { finished = true })
        assertTrue(finished)
        assertFalse(batch.isActive)
    }

    @Test
    fun nestedRunCannotReplaceOuterDeferralAndOtherRunUpdatesAreNeverDeferred() {
        var sorts = 0
        val batch = AgentRunReplayBatch { _, messages -> sorts++; messages }
        val messages = listOf<AgentChatMessageUi>(UserMessageUi("user-outer", "task"))
        batch.replay("outer", emptyList(), reset = {
            try {
                batch.replay("inner", emptyList(), {}, {}, {})
                fail("Nested replay must fail")
            } catch (_: IllegalStateException) {
                assertTrue(batch.isActive)
            }
            assertSame(messages, batch.normalize("outer", messages))
            assertEquals(0, sorts)
            assertSame(messages, batch.normalize("other", messages))
            assertEquals(1, sorts)
        }, apply = {}, finish = { assertFalse(batch.isActive) })
        assertFalse(batch.isActive)
    }

    private fun assertLargeReplay(events: List<AgentEvent>, expectedApplications: Int, expectedBodySize: Int) {
        val history = terminalOrderLargeHistory()
        val runId = "restored-anonymous-7"
        val user = UserMessageUi("user-$runId", "restore this run")
        // resetForReplay deliberately keeps a virtual completion notice. Late
        // restored bodies must be moved before it only at the final boundary.
        val notice = SystemNoticeMessageUi("virtual-completed-$runId", SystemNoticeCode.Completed, "saved completion")
        val initial = history + listOf(user, notice, AgentMessageUi("assistant-$runId-0-0", "stale"))
        var messages: List<AgentChatMessageUi> = initial
        var sorts = 0
        var summaries = 0
        var applications = 0
        val batch = AgentRunReplayBatch { owner, input ->
            sorts++
            normalizeTerminalRunMessages(owner, input)
        }
        val projector = AgentRunMessageProjector { 1_000L }
        fun summary() { if (!batch.isActive) summaries++ }
        fun replay() {
            batch.replay(runId, events, reset = {
                messages = batch.normalize(runId, projector.resetForReplay(runId, messages))
            }, apply = { event ->
                applications++
                messages = batch.normalize(runId, project(projector, runId, event, messages))
                summary()
                assertEquals("No per-event full-history normalization", 0, sorts)
            }, finish = {
                messages = batch.normalize(runId, messages)
                summary()
            })
        }
        replay()
        assertEquals(1, sorts)
        assertEquals(1, summaries)
        assertEquals(expectedApplications, applications)
        assertEquals(history, messages.take(history.size))
        assertEquals(history.size + expectedBodySize + 2, messages.size)
        assertEquals(user, messages[history.size])
        assertEquals(notice, messages.last())
        assertTrue(messages.filterIsInstance<AgentMessageUi>().filter { it.id.startsWith("assistant-$runId-") }
            .all { it.content == "xy" && !it.isStreaming })
        assertTrue(messages.filterIsInstance<ThinkingMessageUi>().filter { it.id.startsWith("$runId-thinking-") }
            .all { it.content == "xy" && !it.isStreaming })
        assertEquals(messages.size, messages.map { it.id }.toSet().size)

        // Batched derived work must match the original event-by-event projection's
        // payloads and order, without reintroducing per-event sorting in the oracle.
        val serialProjector = AgentRunMessageProjector { 1_000L }
        var serial = serialProjector.resetForReplay(runId, initial)
        events.forEach { serial = project(serialProjector, runId, it, serial) }
        serial = normalizeTerminalRunMessages(runId, serial)
        assertEquals(serial, messages)

        val first = messages
        sorts = 0; summaries = 0; applications = 0
        replay()
        assertEquals("Repeated restore is idempotent", first, messages)
        assertEquals(1, sorts)
        assertEquals(1, summaries)
    }

    private fun project(
        projector: AgentRunMessageProjector,
        runId: String,
        event: AgentEvent,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> = when (event) {
        is AgentEvent.AssistantBlockStart -> projector.startAssistantBlock(runId, event, messages)
        is AgentEvent.AssistantBlockDelta -> when (event.kind) {
            AgentEvent.AssistantBlockKind.TEXT -> projector.appendTextDelta(runId, event.round, event.index, event.delta, messages)
            AgentEvent.AssistantBlockKind.THINKING -> projector.appendReasoningDelta(runId, event.round, event.index, event.delta, messages)
            AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
        }
        is AgentEvent.AssistantBlockEnd -> when (event.kind) {
            AgentEvent.AssistantBlockKind.TEXT -> projector.finalizeTextBlock(runId, event.round, event.index, event.replacementContent, messages)
            AgentEvent.AssistantBlockKind.THINKING -> projector.finalizeThinkingBlock(runId, event.round, event.index, event.replacementContent, messages)
            AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
        }
        is AgentEvent.ToolStarted -> projector.startTool(runId, event,
            projector.finalizeTextRound(runId, event.round, projector.finalizeThinkingRound(runId, event.round, messages)))
        is AgentEvent.ToolFinished -> projector.finishTool(runId, event, messages)
        else -> error("Unexpected fixture event: $event")
    }

    private fun MutableList<AgentEvent>.addBlock(round: Int, kind: AgentEvent.AssistantBlockKind) {
        add(AgentEvent.AssistantBlockStart(round, kind, 0))
        add(AgentEvent.AssistantBlockDelta(round, kind, 0, 1, "x"))
        add(AgentEvent.AssistantBlockDelta(round, kind, 0, 1, "y"))
        add(AgentEvent.AssistantBlockEnd(round, kind, 0, contentChars = 2))
    }

    private fun delta(round: Int, index: Int, text: String) =
        AgentEvent.AssistantBlockDelta(round, AgentEvent.AssistantBlockKind.TEXT, index, text.length, text)
}
