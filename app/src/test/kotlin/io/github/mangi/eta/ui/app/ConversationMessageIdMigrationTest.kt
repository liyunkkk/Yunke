package io.github.mangi.eta.ui.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationMessageIdMigrationTest {
    private val original = listOf("old-user", "old-supplement", "assistant")
    private val migrated = listOf("user-turn", "user-turn-supplement-tail", "assistant")
    private val renames = listOf("old-user" to "user-turn",
        "old-supplement" to "user-turn-supplement-tail")

    private fun apply(
        occupied: Set<String> = emptySet(),
        stored: Set<String> = original.toSet(),
        plan: List<Pair<String, String>> = renames,
        queries: MutableList<String> = mutableListOf(),
        writes: MutableList<Pair<String, String>> = mutableListOf(),
    ): List<String> = runBlocking {
        ConversationMessageIdMigration.apply(original, migrated, stored, plan,
            targetExists = { queries += it; it in occupied },
            rename = { old, new ->
                assertEquals("all preflight reads must precede the first write", plan.size, queries.size)
                writes += old to new
                1
            })
    }

    @Test fun freeTargetsPublishOnlyAfterEveryRename() {
        val queries = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        assertSame(migrated, apply(queries = queries, writes = writes))
        assertEquals(renames.map { it.second }, queries)
        assertEquals(renames, writes)
    }

    @Test fun targetOccupiedByAnotherConversationRetainsAllOriginalIds() {
        val writes = mutableListOf<Pair<String, String>>()
        assertSame(original, apply(occupied = setOf("user-turn"), writes = writes))
        assertTrue(writes.isEmpty())
    }

    @Test fun laterSupplementCollisionPreventsEvenTheFirstRename() {
        val queries = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        assertSame(original, apply(occupied = setOf("user-turn-supplement-tail"),
            queries = queries, writes = writes))
        assertEquals(renames.map { it.second }, queries)
        assertTrue(writes.isEmpty())
    }

    @Test fun undecodableStoredRowStillBlocksMigration() {
        val queries = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String>>()
        assertSame(original, apply(stored = original.toSet() + "user-turn", queries = queries, writes = writes))
        assertTrue(queries.isEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test fun missingSourceAndDuplicateTargetsAreRejectedBeforeWrites() {
        for (plan in listOf(listOf("missing" to "user-turn"),
            listOf("old-user" to "same", "old-supplement" to "same"),
            listOf("old-user" to "one", "old-user" to "two"))) {
            val queries = mutableListOf<String>()
            val writes = mutableListOf<Pair<String, String>>()
            assertSame(original, apply(plan = plan, queries = queries, writes = writes))
            assertTrue(queries.isEmpty())
            assertTrue(writes.isEmpty())
        }
    }

    @Test fun occupiedSourcesAndCyclesAreConservativelyRejected() {
        val writes = mutableListOf<Pair<String, String>>()
        assertSame(original, apply(plan = listOf("old-user" to "old-supplement",
            "old-supplement" to "old-user"), writes = writes))
        assertTrue(writes.isEmpty())
    }

    @Test fun alreadyStableAndEmptyPlansDoNotQueryOrWrite() {
        for (plan in listOf(emptyList(), listOf("old-user" to "old-user"))) {
            val queries = mutableListOf<String>()
            val writes = mutableListOf<Pair<String, String>>()
            assertSame(original, apply(plan = plan, queries = queries, writes = writes))
            assertTrue(queries.isEmpty())
            assertTrue(writes.isEmpty())
        }
    }

    @Test fun repeatedBlockedLoadKeepsTheSameIdsAndNeverWrites() {
        val writes = mutableListOf<Pair<String, String>>()
        repeat(2) {
            assertSame(original, apply(occupied = setOf("user-turn-supplement-tail"), writes = writes))
        }
        assertTrue(writes.isEmpty())
    }

    @Test fun unexpectedReadAndWriteFailuresAreNotSwallowed() {
        val failure = IllegalArgumentException("synthetic failure")
        val read = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ConversationMessageIdMigration.apply(original, migrated, original.toSet(), renames,
                    targetExists = { throw failure }, rename = { _, _ -> fail("must not write"); 1 })
            }
        }
        assertSame(failure, read)
        val write = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ConversationMessageIdMigration.apply(original, migrated, original.toSet(), renames,
                    targetExists = { false }, rename = { _, _ -> throw failure })
            }
        }
        assertSame(failure, write)
    }

    @Test fun unexpectedRowCountFailsInsteadOfPublishingUnpersistedIds() {
        for (count in listOf(0, 2)) {
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    ConversationMessageIdMigration.apply(original, migrated, original.toSet(), renames,
                        targetExists = { false }, rename = { _, _ -> count })
                }
            }
        }
    }
}
