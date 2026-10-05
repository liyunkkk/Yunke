package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AgentTimelineProjectionCacheTest {
    private class Fixture {
        var builds = 0
        val cache = AgentTimelineProjectionCache { input ->
            builds++
            input.toTimelineEntries()
        }

        fun project(messages: List<AgentChatMessageUi>): List<AgentTimelineEntry> {
            val inputBefore = messages.toList()
            val oracleBefore = inputBefore.toTimelineEntries()
            val actual = cache.project(messages)
            assertEquals("caller input must not be mutated", inputBefore, messages)
            assertEquals("oracle captured before cache execution", oracleBefore, actual)
            return actual
        }
    }

    private fun assertEquivalent(messages: List<AgentChatMessageUi>, actual: List<AgentTimelineEntry>) {
        val expected = messages.toTimelineEntries()
        assertEquals(expected, actual)
        assertEquals(expected.map { it.key }, actual.map { it.key })
        assertEquals(expected.userMessageIndices(), actual.userMessageIndices())
        for (streaming in listOf(false, true)) {
            for (expansion in listOf<Boolean?>(null, false, true)) {
                val groups = expected.filterIsInstance<AgentTimelineEntry.WorkProcess>()
                val overrides: Map<String, Boolean> = if (expansion == null) emptyMap() else groups.associate { it.key to expansion }
                val retained = groups.associate { it.key to it.messages.take(1).map { m -> "work-step:${m.id}" }.toSet() }
                assertEquals(expected.toLazyTimelineRows(overrides, streaming, retained), actual.toLazyTimelineRows(overrides, streaming, retained))
            }
        }
        assertEquals(initialTimelineItemIndex(expected, false, false, false), initialTimelineItemIndex(actual, false, false, false))
        assertEquals(initialTimelineItemIndex(expected, true, false, false), initialTimelineItemIndex(actual, true, false, false))
    }

    @Test fun longHistoryAssistantDeltasAvoidFullProjectionAndKeepOldSnapshots() {
        val fixture = Fixture()
        var input: List<AgentChatMessageUi> = buildList {
            repeat(50) { run ->
                add(UserMessageUi("user-r$run", "task"))
                repeat(65) { step -> add(ThinkingMessageUi("r$run-thinking-$step", "work", false)) }
                add(SystemNoticeMessageUi("interrupted-r$run", SystemNoticeCode.Interrupted))
                add(AgentMessageUi("assistant-r$run-1", "historical answer"))
            }
            add(UserMessageUi("user-live", "next"))
            add(AgentMessageUi("assistant-live-1", "start", true))
        }
        val originalInput = input
        val originalProjection = fixture.project(input)
        var previous = originalProjection
        repeat(16) { delta ->
            val oldInput = input
            val updated = (input.last() as AgentMessageUi).copy(
                content = "current-$delta", isStreaming = delta < 15,
                renderMarkdown = delta % 2 == 0, generatedAtMillis = delta.toLong(),
                usage = if (delta < 15) null else TokenUsageUi(inputTokens = 1_234, outputTokens = 82),
            )
            input = input.dropLast(1) + updated
            val actual = fixture.project(input)
            assertEquivalent(input, actual)
            assertEquals(oldInput.toTimelineEntries(), previous)
            assertSame(updated, (actual.last() as AgentTimelineEntry.Message).message)
            assertNotSame(previous, actual)
            previous.zip(actual).dropLast(1).forEach { (old, current) -> assertSame(old, current) }
            previous = actual
        }
        assertEquals(1, fixture.builds)
        assertEquals(originalInput.toTimelineEntries(), originalProjection)
    }

    @Test fun relocatedAssistantIsUpdatedAtProjectedSlotNotRawSlot() {
        val fixture = Fixture()
        val input: List<AgentChatMessageUi> = listOf(
            UserMessageUi("user-run", "task"),
            ThinkingMessageUi("run-thinking-1", "work", false),
            SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.Interrupted),
            AgentMessageUi("assistant-run-2", "body", true),
        )
        val before = fixture.project(input)
        assertEquals(listOf("user-run", "work-run-thinking-1", "assistant-run-2", "interrupted-run"), before.map { it.key })
        val latest = (input.last() as AgentMessageUi).copy(content = "latest", isStreaming = false)
        val current = input.dropLast(1) + latest
        val after = fixture.project(current)
        assertEquivalent(current, after)
        assertSame(latest, (after[2] as AgentTimelineEntry.Message).message)
        assertEquals(input.toTimelineEntries(), before)
        assertEquals(1, fixture.builds)
    }

    @Test fun duplicateAssistantIdsAlwaysUseOriginalLatestPayloadRules() {
        val fixture = Fixture()
        val first = AgentMessageUi("assistant-run-1", "old")
        val latest = first.copy(content = "latest")
        val input: List<AgentChatMessageUi> = listOf(UserMessageUi("user-run", "task"), first,
            SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.Interrupted), latest)
        assertEquivalent(input, fixture.project(input))
        val update = input.dropLast(1) + latest.copy(content = "new latest")
        assertEquivalent(update, fixture.project(update))
        assertEquals(2, fixture.builds)
    }

    @Test fun duplicateIdsAcrossTypesAlsoDisableTheFastPath() {
        val fixture = Fixture()
        val input: List<AgentChatMessageUi> = listOf(ThinkingMessageUi("same", "work", false), AgentMessageUi("same", "body"))
        fixture.project(input)
        val update = input.dropLast(1) + (input.last() as AgentMessageUi).copy(content = "new")
        assertEquivalent(update, fixture.project(update))
        assertEquals(2, fixture.builds)
    }

    @Test fun structuralAndNonAssistantReplacementsFallBack() {
        val base: List<AgentChatMessageUi> = listOf(
            UserMessageUi("user-run", "task"), ThinkingMessageUi("run-thinking-1", "work", true),
            ToolActivityMessageUi("run-tool-1-read", "read_file", ToolActivityStatusUi.Running, argumentsSummary = "read"),
            SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.ModelRetry),
            AgentMessageUi("assistant-run-1", "body", true),
        )
        val variants: List<List<AgentChatMessageUi>> = listOf(
            base + AgentMessageUi("new", "append"), base.dropLast(1), base.reversed(),
            base.toMutableList().apply { this[4] = (base[4] as AgentMessageUi).copy(id = "different") },
            base.toMutableList().apply { this[4] = ThinkingMessageUi("assistant-run-1", "different type", false) },
            base.toMutableList().apply { this[0] = (base[0] as UserMessageUi).copy(content = "edit") },
            base.toMutableList().apply { this[1] = (base[1] as ThinkingMessageUi).copy(isStreaming = false) },
            base.toMutableList().apply { this[2] = (base[2] as ToolActivityMessageUi).copy(status = ToolActivityStatusUi.Success) },
            base.toMutableList().apply { this[3] = (base[3] as SystemNoticeMessageUi).copy(code = SystemNoticeCode.Completed) },
            base.toMutableList().apply { this[0] = UserMessageUi("user-run-supplement-resume", "hidden") },
        )
        variants.forEach { current ->
            val fixture = Fixture()
            val old = fixture.project(base)
            assertEquivalent(current, fixture.project(current))
            assertEquals(2, fixture.builds)
            assertEquals(base.toTimelineEntries(), old)
        }
    }

    @Test fun fullRebuildRefreshesTheMappingBeforeTheNextAssistantDelta() {
        val fixture = Fixture()
        val first: List<AgentChatMessageUi> = listOf(UserMessageUi("user-r", "task"), AgentMessageUi("assistant-r-1", "one"))
        val old = fixture.project(first)
        val moved = listOf(first[0], SystemNoticeMessageUi("interrupted-r", SystemNoticeCode.Interrupted), first[1])
        assertEquivalent(moved, fixture.project(moved))
        val latest = (first[1] as AgentMessageUi).copy(content = "two")
        val next = moved.dropLast(1) + latest
        val result = fixture.project(next)
        assertEquivalent(next, result)
        assertSame(latest, (result[1] as AgentTimelineEntry.Message).message)
        assertEquals(2, fixture.builds)
        assertEquals(first.toTimelineEntries(), old)
    }

    @Test fun unchangedReferencesReuseOutputButMultipleAssistantSlotsUpdate() {
        val fixture = Fixture()
        val base: List<AgentChatMessageUi> = listOf(AgentMessageUi("a", "one"), AgentMessageUi("b", "two"))
        val old = fixture.project(base)
        assertSame(old, fixture.project(base.toList()))
        val current = base.map { val agent = it as AgentMessageUi; agent.copy(content = agent.content + " more") }
        val new = fixture.project(current)
        assertEquivalent(current, new)
        current.zip(new).forEach { (message, entry) -> assertSame(message, (entry as AgentTimelineEntry.Message).message) }
        assertEquals(1, fixture.builds)
        assertEquals(base.toTimelineEntries(), old)
    }

    @Test fun laterIncompatibleSlotDoesNotMutatePreviouslyReturnedEntries() {
        val fixture = Fixture()
        val base: List<AgentChatMessageUi> = listOf(AgentMessageUi("a", "one"), UserMessageUi("user", "task"))
        val old = fixture.project(base)
        val current = listOf((base[0] as AgentMessageUi).copy(content = "more"), (base[1] as UserMessageUi).copy(content = "edited"))
        assertEquivalent(current, fixture.project(current))
        assertEquals(base.toTimelineEntries(), old)
        assertEquals(2, fixture.builds)
    }

    @Test fun reusedMutableSourceContainerCannotChangeTheCachedSnapshot() {
        val fixture = Fixture()
        val source = mutableListOf<AgentChatMessageUi>(AgentMessageUi("a", "original"))
        val old = fixture.project(source)
        source[0] = (source[0] as AgentMessageUi).copy(content = "new")
        assertEquivalent(source, fixture.project(source))
        assertEquals("original", ((old.single() as AgentTimelineEntry.Message).message as AgentMessageUi).content)
        assertEquals(1, fixture.builds)
    }

    @Test fun emptyRemovalAndNewCacheDoNotRetainOldConversationPayloads() {
        val fixture = Fixture()
        val empty = fixture.project(emptyList())
        assertSame(empty, fixture.project(emptyList()))
        fixture.project(listOf(AgentMessageUi("same", "old conversation")))
        assertTrue(fixture.project(emptyList()).isEmpty())
        val next = listOf<AgentChatMessageUi>(AgentMessageUi("same", "new conversation"))
        assertEquivalent(next, fixture.project(next))
        assertEquivalent(next, AgentTimelineProjectionCache().project(next))
        assertEquals(4, fixture.builds)
    }

    @Test fun unexpectedProjectionIdentityOrMissingMappingFailsClosed() {
        listOf<(List<AgentChatMessageUi>) -> List<AgentTimelineEntry>>(
            { emptyList() },
            { input -> listOf(AgentTimelineEntry.Message((input.single() as AgentMessageUi).copy())) },
            { input -> listOf(AgentTimelineEntry.Message(input.single()), AgentTimelineEntry.Message(input.single())) },
        ).forEach { build ->
            var count = 0
            val cache = AgentTimelineProjectionCache { input -> count++; build(input) }
            cache.project(listOf(AgentMessageUi("a", "old")))
            cache.project(listOf(AgentMessageUi("a", "new")))
            assertEquals(2, count)
        }
    }

    @Test fun seededMixedChangesMatchLegacyGroupingOrderingAndEveryRowPolicy() {
        val random = Random(412)
        val fixture = Fixture()
        var serial = 0
        fun message(): AgentChatMessageUi {
            val id = "r${serial++}"
            return when (random.nextInt(5)) {
                0 -> UserMessageUi("user-$id", "task")
                1 -> ThinkingMessageUi("$id-thinking-1", "work", true)
                2 -> ToolActivityMessageUi("$id-tool-1-read", "read_file", ToolActivityStatusUi.Running, argumentsSummary = "read")
                3 -> SystemNoticeMessageUi("interrupted-$id", SystemNoticeCode.entries[random.nextInt(SystemNoticeCode.entries.size)])
                else -> AgentMessageUi("assistant-$id-1", "body", true)
            }
        }
        var input: List<AgentChatMessageUi> = List(70) { message() }
        var previous = fixture.project(input)
        repeat(240) { step ->
            val oldInput = input.toList()
            val next = input.toMutableList()
            when (random.nextInt(6)) {
                0 -> next.add(random.nextInt(next.size + 1), message())
                1 -> if (next.isNotEmpty()) next.removeAt(random.nextInt(next.size))
                2 -> if (next.size > 1) {
                    val a = random.nextInt(next.size); val b = random.nextInt(next.size)
                    val tmp = next[a]; next[a] = next[b]; next[b] = tmp
                }
                3 -> if (next.isNotEmpty()) {
                    val i = random.nextInt(next.size); val old = next[i]
                    next[i] = when (old) {
                        is AgentMessageUi -> old.copy(content = "delta-$step", isStreaming = step % 2 == 0)
                        is ThinkingMessageUi -> old.copy(content = "work-$step", isStreaming = false)
                        is ToolActivityMessageUi -> old.copy(status = ToolActivityStatusUi.Success)
                        is SystemNoticeMessageUi -> old.copy(code = SystemNoticeCode.Completed)
                        else -> message()
                    }
                }
                4 -> if (next.isNotEmpty()) next.add(next[random.nextInt(next.size)])
                else -> if (next.isNotEmpty()) next[random.nextInt(next.size)] = message()
            }
            input = next
            val projected = fixture.project(input)
            assertEquivalent(input, projected)
            assertEquals(oldInput.toTimelineEntries(), previous)
            previous = projected
        }
    }
}
