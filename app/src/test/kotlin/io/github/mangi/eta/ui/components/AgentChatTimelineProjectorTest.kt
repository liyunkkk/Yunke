package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatTimelineProjectorTest {
    private val streaming = AgentChatProjectionContext("chat-a", null, isStreaming = true)

    private class Counters {
        var visible = 0
        var entries = 0
        var metadata = 0
        val projector = AgentChatTimelineProjector(
            projectVisible = { messages, target ->
                visible++
                visibleChatProjectionMessages(messages, target)
            },
            projectEntries = { messages -> entries++; messages.toTimelineEntries() },
            projectMetadata = { messages, entries -> metadata++; timelineMetadata(messages, entries) },
        )
        fun assertFullCount(expected: Int) {
            assertEquals(expected, visible)
            assertEquals(expected, entries)
            assertEquals(expected, metadata)
        }
    }

    @Test fun textDeltasReuseProjectionAndKeepEveryPriorSnapshotIntact() {
        val counts = Counters()
        val prefix = List<AgentChatMessageUi>(10_000) { UserMessageUi("user-$it", "q") }
        val initial = prefix + AgentMessageUi("tail", "a", isStreaming = true)
        val first = counts.projector.project(initial, streaming)
        var previous = first
        repeat(100) { delta ->
            val tail = AgentMessageUi("tail", "text-$delta", isStreaming = true)
            val next = counts.projector.project(prefix + tail, streaming, previous)
            assertSame(tail, next.visibleMessages.last())
            assertSame(tail, (next.entries.last() as AgentTimelineEntry.Message).message)
            assertSame(first.entries.first(), next.entries.first())
            assertSame(first.metadata, next.metadata)
            assertSame(first.visibleBase, next.visibleBase)
            assertSame(first.entriesBase, next.entriesBase)
            assertSame(previous.sourceMessages.last(), previous.visibleMessages.last())
            previous = next
        }
        counts.assertFullCount(1)
        assertEquals("a", (first.visibleMessages.last() as AgentMessageUi).content)
        assertSame(first, counts.projector.project(initial, streaming, first))
    }

    @Test fun abandonedCompositionDoesNotAdvanceBaseOrLeakItsNewMessage() {
        val counts = Counters()
        val initial = listOf(UserMessageUi("u", "q"), AgentMessageUi("a", "one", true))
        val committed = counts.projector.project(initial, streaming)
        val discarded = counts.projector.project(initial + UserMessageUi("discarded", "q"), streaming, committed)
        val current = initial.dropLast(1) + (initial.last() as AgentMessageUi).copy(content = "two")
        val result = counts.projector.project(current, streaming, committed)
        counts.assertFullCount(2)
        assertSame(committed.metadata, result.metadata)
        assertFalse(result.visibleMessages.any { it.id == "discarded" })
        assertTrue(discarded.visibleMessages.any { it.id == "discarded" })
        assertEquals("one", (committed.visibleMessages.last() as AgentMessageUi).content)
    }

    @Test fun appendDeleteAndSameIdPrefixReplacementAlwaysRebuild() {
        val counts = Counters()
        val initial = listOf(UserMessageUi("u", "q"), AgentMessageUi("a", "one", true))
        val first = counts.projector.project(initial, streaming)
        val appended = initial + AgentMessageUi("b", "new answer", true)
        val next = counts.projector.project(appended, streaming, first)
        counts.assertFullCount(2)
        assertEquals(appended, next.visibleMessages)
        assertEquals(appended.toTimelineEntries(), next.entries)
        val removed = counts.projector.project(initial, streaming, next)
        counts.assertFullCount(3)
        val replaced = listOf(initial[0].let { (it as UserMessageUi).copy(content = "edited") }, initial[1])
        val edited = counts.projector.project(replaced, streaming, removed)
        counts.assertFullCount(4)
        assertEquals(replaced, edited.visibleMessages)
    }

    @Test fun allNonTextChangesAndControlBoundariesRebuildSynchronously() {
        val tail = AgentMessageUi("a", "partial", true)
        val initial = listOf(UserMessageUi("u", "q"), tail)
        val changes = listOf(
            tail.copy(id = "new-id"), tail.copy(isStreaming = false),
            tail.copy(renderMarkdown = false), tail.copy(usage = TokenUsageUi(outputTokens = 3)),
            tail.copy(generatedAtMillis = 123L), tail.copy(content = "   "),
        )
        changes.forEach { changed ->
            val counts = Counters()
            val first = counts.projector.project(initial, streaming)
            val messages = initial.dropLast(1) + changed
            val next = counts.projector.project(messages, streaming, first)
            counts.assertFullCount(2)
            assertEquals(visibleChatProjectionMessages(messages, null), next.visibleMessages)
            assertNotSame(first.metadata, next.metadata)
        }
        listOf(
            streaming.copy(conversationId = "chat-b"), streaming.copy(isStreaming = false),
            streaming.copy(isPaused = true), streaming.copy(isCompressingContext = true),
            streaming.copy(isWaitingForCompression = true), streaming.copy(editTargetMessageId = "u"),
        ).forEach { context ->
            val counts = Counters()
            val first = counts.projector.project(initial, streaming)
            val next = counts.projector.project(initial, context, first)
            counts.assertFullCount(2)
            assertEquals(context, next.context)
            assertEquals(visibleChatProjectionMessages(initial, context.editTargetMessageId), next.visibleMessages)
        }
        val counts = Counters()
        val first = counts.projector.project(initial, streaming)
        val finalized = initial.dropLast(1) + tail.copy(content = "full", isStreaming = false)
        val finalSnapshot = counts.projector.project(finalized, streaming.copy(isStreaming = false), first)
        val rows = finalSnapshot.entries.toLazyTimelineRows(emptyMap(), isStreaming = false)
        assertSame(finalized.last(), rows.turnFooters().values.single())
        assertEquals("partial", (first.visibleMessages.last() as AgentMessageUi).content)
    }

    @Test fun compressionFiltersEditAndSessionSwitchMatchOriginalFullProjection() {
        val messages = listOf(
            UserMessageUi("user-run", "question"), ThinkingMessageUi("run-thinking-1", "reasoning", false),
            UserMessageUi("user-run-supplement-resume", "hidden resume"),
            SystemNoticeMessageUi("retry", SystemNoticeCode.ModelRetry),
            ContextCompactedMessageUi("compacted", 1, "summary"),
            AgentMessageUi("empty", " "), AgentMessageUi("tail", "partial", true),
        )
        val counts = Counters()
        val first = counts.projector.project(messages, streaming)
        val changed = messages.dropLast(1) + (messages.last() as AgentMessageUi).copy(content = "more")
        val delta = counts.projector.project(changed, streaming, first)
        counts.assertFullCount(1)
        assertEquals(visibleChatProjectionMessages(changed, null).toTimelineEntries(), delta.entries)
        assertFalse(delta.entries.any { it.key == "retry" || it.key == "user-run-supplement-resume" })
        assertTrue(delta.entries.any { it.key == "compacted" })
        val edit = counts.projector.project(changed, streaming.copy(editTargetMessageId = "compacted"), delta)
        counts.assertFullCount(2)
        assertFalse(edit.metadata.visibleMessageIds.contains("tail"))
        val restored = counts.projector.project(changed, streaming, edit)
        counts.assertFullCount(3)
        assertEquals(delta.entries, restored.entries)
        val switched = counts.projector.project(changed, streaming.copy(conversationId = "chat-b"), restored)
        counts.assertFullCount(4)
        assertEquals(restored.entries, switched.entries)
    }

    @Test fun groupBatchExpansionNavigationAndFooterAnchorsStayIdentical() {
        val counts = Counters()
        val work = List(33) { ThinkingMessageUi("step-$it", "work-$it", false) }
        val initial = listOf(UserMessageUi("u", "q")) + work + AgentMessageUi("tail", "partial", true)
        val first = counts.projector.project(initial, streaming)
        val updated = initial.dropLast(1) + (initial.last() as AgentMessageUi).copy(content = "more")
        val delta = counts.projector.project(updated, streaming, first)
        counts.assertFullCount(1)
        val expected = updated.toTimelineEntries()
        assertSame(first.entries[1], delta.entries[1])
        assertSame(first.entries[2], delta.entries[2])
        for (expanded in listOf(false, true)) {
            val overrides = delta.metadata.workGroups.keys.associateWith { expanded }
            val actualRows = delta.entries.toLazyTimelineRows(overrides, true)
            val expectedRows = expected.toLazyTimelineRows(overrides, true)
            assertEquals(expectedRows, actualRows)
            assertEquals(expectedRows.lazyUserMessageIndices(), actualRows.lazyUserMessageIndices())
            assertEquals(expectedRows.turnFooters(true, includeOpenTurnForBranch = true),
                actualRows.turnFooters(true, includeOpenTurnForBranch = true))
            assertEquals("tail", actualRows.turnFooters(true, includeOpenTurnForBranch = true).keys.single())
        }
        val changedWork = updated.toMutableList().apply { this[32] = work[31].copy(content = "changed") }
        val rebuilt = counts.projector.project(changedWork, streaming, delta)
        counts.assertFullCount(2)
        assertEquals(changedWork.toTimelineEntries(), rebuilt.entries)
        assertEquals(listOf(32, 1), rebuilt.metadata.workGroups.values.map { it.messages.size })
        val newWork = ThinkingMessageUi("step-33", "new work", true)
        val appended = updated.dropLast(1) + newWork + updated.last()
        val newMessage = counts.projector.project(appended, streaming, rebuilt)
        counts.assertFullCount(3)
        assertEquals(appended.toTimelineEntries(), newMessage.entries)
        assertEquals(listOf(32, 2), newMessage.metadata.workGroups.values.map { it.messages.size })
    }

    @Test fun normalizedLateBodyOrRepeatedIdNeverUsesTailOverlay() {
        val cases = listOf(
            listOf(UserMessageUi("user-run", "q"), SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.Stopped),
                AgentMessageUi("assistant-run-1", "late", true)),
            listOf(UserMessageUi("u", "q"), AgentMessageUi("a", "earlier"), AgentMessageUi("a", "tail", true)),
        )
        cases.forEach { messages ->
            val counts = Counters()
            val first = counts.projector.project(messages, streaming)
            val changed = messages.dropLast(1) + (messages.last() as AgentMessageUi).copy(content = "new")
            val next = counts.projector.project(changed, streaming, first)
            counts.assertFullCount(2)
            assertEquals(changed.toTimelineEntries(), next.entries)
        }
    }

    @Test fun orphanSpeechSkipsHistoryForUnboundOwnerAndUsesExactCompletedReply() {
        val unreadable = object : AbstractList<AgentChatMessageUi>() {
            override val size: Int get() = error("Must not scan")
            override fun get(index: Int): AgentChatMessageUi = error("Must not scan")
        }
        for (owner in listOf(null, "", "tts-preview", "agent-tts", "voice-mode-123")) {
            assertFalse(shouldStopOrphanSpeechPlaybackForMessages(owner, false, unreadable))
        }
        assertTrue(shouldStopOrphanSpeechPlaybackForMessages("a", true, unreadable))
        assertFalse(shouldStopOrphanSpeechPlaybackForMessages("a", false, listOf(AgentMessageUi("a", "done"))))
        assertTrue(shouldStopOrphanSpeechPlaybackForMessages("a", false, listOf(AgentMessageUi("a", "live", true))))
    }
}
