package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class AgentTimelineRowsCacheTest {
    private class Fixture {
        var builds = 0
        val cache = AgentTimelineRowsCache { entries, expanded, streaming, retained ->
            builds++
            entries.toLazyTimelineRows(expanded, streaming, retained)
        }
        fun project(
            entries: List<AgentTimelineEntry>,
            expanded: Map<String, Boolean> = emptyMap(),
            streaming: Boolean = true,
            retained: Map<String, Set<String>> = emptyMap(),
        ): List<AgentTimelineRow> {
            val before = entries.toList()
            return cache.project(entries, expanded, streaming, retained).also { rows ->
                val expected = before.toLazyTimelineRows(expanded, streaming, retained)
                assertEquals(expected, rows)
                assertEquals(expected.lazyUserMessageIndices(), rows.lazyUserMessageIndices())
                for (paused in listOf(false, true)) {
                    assertEquals(expected.turnFooters(streaming, false, paused), rows.turnFooters(streaming, false, paused))
                }
                assertEquals(before, entries)
            }
        }
    }

    private fun input(): List<AgentChatMessageUi> = listOf(
        UserMessageUi("user-run", "task"),
        ThinkingMessageUi("run-thinking-1", "work", false),
        ThinkingMessageUi("run-thinking-2", "more work", false),
        SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.Completed),
        AgentMessageUi("assistant-run-1", "answer", true),
    )

    @Test fun sameSlotAssistantDeltaPatchesOnlyItsRowAndKeepsLatestFooterOwner() {
        for (expanded in listOf(false, true)) {
            val fixture = Fixture()
            val projection = AgentTimelineProjectionCache()
            val source = input()
            val entries = projection.project(source)
            val groupKey = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>().single().key
            val overrides = mapOf(groupKey to expanded)
            val retained = mapOf(groupKey to setOf("work-step:run-thinking-2"))
            val old = fixture.project(entries, overrides, false, retained)
            val latest = (source.last() as AgentMessageUi).copy(content = "answer appended", isStreaming = false)
            val next = projection.project(source.dropLast(1) + latest)
            val rows = fixture.project(next, overrides, false, retained)
            assertEquals(1, fixture.builds)
            assertNotSame(old, rows)
            rows.zip(old).forEach { (current, previous) ->
                if (current is AgentTimelineRow.Message && current.message is AgentMessageUi) {
                    assertSame(latest, current.message)
                } else assertSame(previous, current)
            }
            // Do not cache footer owners with the row skeleton. The notice anchor
            // must still dispatch actions to the NEW assistant payload.
            assertSame(latest, rows.turnFooters(false, false, false).values.single())
            assertEquals(source.toTimelineEntries().toLazyTimelineRows(overrides, false, retained), old)
        }
    }

    @Test fun unchangedReferencesReuseOutputAndMultipleAssistantSlotsPatch() {
        val fixture = Fixture()
        val entries: List<AgentTimelineEntry> = listOf(
            AgentTimelineEntry.Message(AgentMessageUi("a", "one")),
            AgentTimelineEntry.Message(AgentMessageUi("b", "two")),
        )
        val old = fixture.project(entries)
        assertSame(old, fixture.project(entries.toList()))
        val current = entries.map { entry ->
            AgentTimelineEntry.Message(((entry as AgentTimelineEntry.Message).message as AgentMessageUi).copy(content = "new"))
        }
        fixture.project(current)
        assertEquals(1, fixture.builds)
    }

    @Test fun insertDeleteReorderBoundaryAndWorkChangesUseFullProjection() {
        val entries = input().toTimelineEntries()
        val variants = listOf(
            entries + AgentTimelineEntry.Message(AgentMessageUi("new", "body")),
            entries.toMutableList().apply { add(1, AgentTimelineEntry.Message(AgentMessageUi("new", "body"))) },
            entries.dropLast(1),
            entries.toMutableList().apply { removeAt(1) },
            entries.reversed(),
            entries.toMutableList().apply { this[0] = AgentTimelineEntry.Message(UserMessageUi("user-run-supplement-resume", "task")) },
            entries.toMutableList().apply { this[0] = AgentTimelineEntry.Message(UserMessageUi("user-run", "edited")) },
            entries.toMutableList().apply { this[1] = (entries[1] as AgentTimelineEntry.WorkProcess).copy(
                messages = listOf(ThinkingMessageUi("run-thinking-1", "work", true))) },
            entries.toMutableList().apply { this[2] = AgentTimelineEntry.Message(AgentMessageUi("different-id", "body")) },
        )
        variants.forEach { current ->
            val fixture = Fixture()
            val old = fixture.project(entries)
            fixture.project(current)
            assertEquals(2, fixture.builds)
            assertEquals(entries.toLazyTimelineRows(emptyMap(), true), old)
        }
    }

    @Test fun policyChangesAlwaysRebuildEvenWithTheSameEntries() {
        val entries = input().toTimelineEntries()
        val key = (entries[1] as AgentTimelineEntry.WorkProcess).key
        val fixture = Fixture()
        fixture.project(entries)
        fixture.project(entries, mapOf(key to false))
        fixture.project(entries, mapOf(key to false), false)
        fixture.project(entries, mapOf(key to false), false, mapOf(key to setOf("work-step:run-thinking-1")))
        assertEquals(4, fixture.builds)
    }

    @Test fun duplicateIdsIncludingHiddenWorkStepsDisableFastPath() {
        val assistant = AgentMessageUi("same", "body", true)
        for (duplicate in listOf<AgentTimelineEntry>(
            AgentTimelineEntry.Message(assistant),
            AgentTimelineEntry.Message(UserMessageUi("same", "task")),
            AgentTimelineEntry.WorkProcess("work-same", listOf(ThinkingMessageUi("same", "work", false))),
        )) {
            val fixture = Fixture()
            val entries = listOf(duplicate, AgentTimelineEntry.Message(assistant))
            fixture.project(entries, streaming = false)
            fixture.project(entries.dropLast(1) + AgentTimelineEntry.Message(assistant.copy(content = "body more")), streaming = false)
            assertEquals(2, fixture.builds)
        }
    }

    @Test fun mutablePolicyAndSourceContainersDoNotRewriteSavedSnapshots() {
        val fixture = Fixture()
        val entries = input().toTimelineEntries().toMutableList()
        val key = (entries[1] as AgentTimelineEntry.WorkProcess).key
        val expanded = mutableMapOf(key to false)
        val retainedIds = mutableSetOf("work-step:run-thinking-1")
        val retained: Map<String, Set<String>> = mapOf(key to retainedIds)
        val old = fixture.project(entries, expanded, false, retained)
        entries[2] = AgentTimelineEntry.Message(AgentMessageUi("assistant-run-1", "latest"))
        val latestRows = fixture.project(entries, expanded, false, retained)
        assertEquals(1, fixture.builds)
        assertEquals("answer", ((old[old.indexOfFirst { it is AgentTimelineRow.Message && it.message is AgentMessageUi }] as AgentTimelineRow.Message).message as AgentMessageUi).content)
        retainedIds.add("work-step:run-thinking-2")
        fixture.project(entries, expanded, false, retained)
        expanded[key] = true
        fixture.project(entries, expanded, false, retained)
        assertEquals(3, fixture.builds)
        assertEquals(1, latestRows.count { it is AgentTimelineRow.WorkStep })
    }

    @Test fun unexpectedRowMappingFailsClosed() {
        var builds = 0
        val cache = AgentTimelineRowsCache { _, _, _, _ -> builds++; emptyList() }
        cache.project(listOf(AgentTimelineEntry.Message(AgentMessageUi("a", "old"))), emptyMap(), true)
        cache.project(listOf(AgentTimelineEntry.Message(AgentMessageUi("a", "new"))), emptyMap(), true)
        assertEquals(2, builds)
    }
}
