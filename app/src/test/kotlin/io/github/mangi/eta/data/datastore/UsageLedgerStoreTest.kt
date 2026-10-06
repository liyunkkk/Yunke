package io.github.mangi.eta.data.datastore

import io.github.mangi.eta.data.repository.ConversationUsageTotals
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.applyModelUsageDelta
import io.github.mangi.eta.data.repository.conversationUsageTotals
import io.github.mangi.eta.data.repository.decodeModelUsageSnapshot
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class UsageLedgerStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stopTimers() { scope.cancel() }

    private class Storage(var value: String? = null) : UsageLedgerStorage {
        var writes = 0
        var failures = 0
        override fun read() = value
        override fun write(raw: String) {
            if (failures > 0) { failures--; throw IOException("simulated incomplete commit") }
            value = raw
            writes++
        }
    }

    private fun delta(request: String = "request", input: Long = 100) = ModelUsageDelta(
        "provider", "Provider", "model", "Model", input, 10, 30, 5,
        conversationId = "owner", requestId = request, atMillis = 1000,
        day = LocalDate.of(2026, 1, 1),
    )

    private fun store(storage: UsageLedgerStorage, interval: Long = 60_000, maxEdits: Int = 64) =
        UsageLedgerStore(storage, { null }, {}, scope, interval, maxEdits)

    @Test fun migrationCopiesEveryByteAndIsIdempotentIncludingFalseInitialization() = runBlocking {
        for (initialized in listOf(false, true)) {
            var legacy: String? = JSONObject(applyModelUsageDelta(null, delta()))
                .put("conversationTotalsInitialized", initialized)
                .put("futureExtension", JSONArray().put("kept")).toString()
            val original = legacy!!
            val disk = Storage()
            var clears = 0
            fun instance() = UsageLedgerStore(disk, { legacy }, { legacy = null; clears++ }, scope)
            val first = instance()
            assertEquals(original, first.snapshot())
            assertEquals(original, disk.value)
            assertNull(legacy)
            assertEquals(1, disk.writes)
            assertEquals(original, first.snapshot())
            assertEquals(original, instance().snapshot())
            assertEquals(1, disk.writes)
            assertEquals(2, clears) // New instance may retry cleanup; it never copies again.
            assertEquals(initialized, JSONObject(disk.value!!).getBoolean("conversationTotalsInitialized"))
        }
    }

    @Test fun incompleteMigrationReadsLegacyAndRetriesWithoutDeletingIt() = runBlocking {
        var legacy: String? = applyModelUsageDelta(null, delta())
        val original = legacy!!
        val disk = Storage().also { it.failures = 2 }
        val ledger = UsageLedgerStore(disk, { legacy }, { legacy = null }, scope)
        assertEquals(original, ledger.rawFlow().first())
        assertEquals(original, ledger.snapshot())
        assertEquals(original, legacy)
        assertNull(disk.value)
        assertEquals(original, ledger.snapshot())
        assertNull(legacy)
        assertEquals(original, disk.value)
        assertEquals(1, disk.writes)
    }

    @Test fun transientReadFailureFallsBackButNeverOverwritesACommittedNewerLedger() = runBlocking {
        val old = applyModelUsageDelta(null, delta(input = 1))
        val newer = applyModelUsageDelta(old, delta(input = 20))
        var legacy: String? = old
        var readFails = true
        var writes = 0
        val disk = object : UsageLedgerStorage {
            override fun read(): String {
                if (readFails) { readFails = false; throw IOException("read interrupted") }
                return newer
            }
            override fun write(raw: String) { writes++; fail("must not overwrite existing ledger") }
        }
        val ledger = UsageLedgerStore(disk, { legacy }, { legacy = null }, scope)
        assertEquals(old, ledger.rawFlow().first())
        assertEquals(old, legacy)
        assertEquals(newer, ledger.snapshot())
        assertNull(legacy)
        assertEquals(0, writes)
    }

    @Test fun crashAfterCopyBeforeCleanupPrefersCommittedFileOverStalePreferences() = runBlocking {
        val old = applyModelUsageDelta(null, delta(input = 1))
        val newer = applyModelUsageDelta(old, delta(input = 9))
        val disk = Storage(newer)
        var legacy: String? = old
        val first = UsageLedgerStore(disk, { legacy }, { throw IOException("cleanup interrupted") }, scope)
        assertEquals(newer, first.snapshot())
        assertEquals(old, legacy)
        val retry = UsageLedgerStore(disk, { legacy }, { legacy = null }, scope)
        assertEquals(newer, retry.snapshot())
        assertNull(legacy)
        assertEquals(0, disk.writes)
    }

    @Test fun orphanPendingFileIsNotACompletedMigrationAndAtomicReplacementSurvivesReload() = runBlocking {
        val directory = Files.createTempDirectory("usage-ledger-test").toFile()
        try {
            val file = File(directory, "ledger.json")
            File(directory, "ledger.json.pending").writeText("{incomplete")
            val storage = AtomicUsageLedgerFile(file)
            val raw = applyModelUsageDelta(null, delta())
            var legacy: String? = raw
            val ledger = UsageLedgerStore(storage, { legacy }, { legacy = null }, scope)
            assertEquals(raw, ledger.snapshot())
            assertNull(legacy)
            ledger.record(delta(input = 12))
            ledger.flush()
            assertEquals(12L, decodeModelUsageSnapshot(AtomicUsageLedgerFile(file).read()).totalInputTokens)
            assertEquals(ledger.snapshot(), file.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun partialsMergeIntoOneCommitAndMatchSequentialWritesIncludingIntermediateSets() = runBlocking {
        val disk = Storage("{}")
        val ledger = store(disk)
        var expected = "{}"
        val changes = (1..20).map { index ->
            delta(input = index.toLong()).copy(day = LocalDate.of(2026, 1, index),
                conversationId = if (index % 2 == 0) "owner" else "alternate")
        }
        for (change in changes) {
            expected = applyModelUsageDelta(expected, change)
            ledger.record(change)
        }
        assertEquals(0, disk.writes)
        assertEquals(conversationUsageTotals(expected, "owner"), ledger.conversationFlow("owner").first())
        ledger.flush()
        assertEquals(1, disk.writes)
        assertEquals(decodeModelUsageSnapshot(expected), decodeModelUsageSnapshot(disk.value))
        val model = decodeModelUsageSnapshot(disk.value).providers.single().models.single()
        assertEquals(20, model.activeDays)
        assertEquals(2, model.conversationCount)
        assertEquals(expected, ledger.snapshot())
        assertEquals(1, disk.writes)
    }

    @Test fun replacementDecreasesProcessTotalsWithoutReadingSerializedLedger() = runBlocking {
        val disk = Storage("{}")
        val ledger = store(disk)
        ledger.record(delta("first", 900))
        ledger.record(delta("second", 300))
        assertEquals(1200L, ledger.conversationFlow("owner").first()!!.input)
        ledger.record(delta("first", 20).copy(outputTokens = 2, cachedTokens = 1, cacheCreationTokens = 0))
        assertEquals(ConversationUsageTotals(320, 12, 31, 5), ledger.conversationFlow("owner").first())
        assertEquals("{}", disk.value)
        ledger.flush()
        assertEquals(320L, decodeModelUsageSnapshot(disk.value).totalInputTokens)
    }

    @Test fun concurrentRequestsAndTheirReplacementsCannotLoseUpdates() = runBlocking {
        val disk = Storage("{}")
        val ledger = store(disk, maxEdits = 64)
        coroutineScope {
            repeat(100) { index -> launch(Dispatchers.Default) {
                ledger.record(delta("request-$index", 100))
                ledger.record(delta("request-$index", 2))
            } }
        }
        assertEquals(200L, ledger.conversationFlow("owner").first()!!.input)
        ledger.flush()
        val model = decodeModelUsageSnapshot(disk.value).providers.single().models.single()
        assertEquals(200L, model.inputTokens)
        assertEquals(1000L, model.outputTokens)
        assertEquals(100, model.events.size)
        assertEquals(4, disk.writes) // 200 edits => 3 count-bounded commits + the final flush.
    }

    @Test fun editBoundFlushesSynchronouslyAndTimerFlushesIdleRequest() = runBlocking {
        val countDisk = Storage("{}")
        val countLedger = store(countDisk, maxEdits = 3)
        repeat(3) { countLedger.record(delta(input = (it + 1).toLong())) }
        assertEquals(1, countDisk.writes)
        assertEquals(3L, decodeModelUsageSnapshot(countDisk.value).totalInputTokens)
        val timedDisk = Storage("{}")
        val timed = store(timedDisk, interval = 20)
        timed.record(delta(input = 7))
        withTimeout(5_000) {
            while (decodeModelUsageSnapshot(timed.rawFlow().first()).totalInputTokens != 7L) delay(10)
        }
        assertEquals(1, timedDisk.writes)
    }

    @Test fun failedCommitRetainsDirtyTotalsAndSnapshotRetriesRatherThanDroppingUsage() = runBlocking {
        val disk = Storage("{}")
        val ledger = store(disk)
        ledger.record(delta(input = 71))
        disk.failures = 1
        try { ledger.snapshot(); fail("must report commit failure") } catch (_: IOException) { }
        assertEquals("{}", disk.value)
        assertEquals(71L, ledger.conversationFlow("owner").first()!!.input)
        assertEquals(71L, decodeModelUsageSnapshot(ledger.snapshot()).totalInputTokens)
        assertEquals(1, disk.writes)
    }

    @Test fun snapshotAndReplacementCoverPendingUsageAndResetConversationCache() = runBlocking {
        val disk = Storage("{}")
        val ledger = store(disk)
        ledger.record(delta(input = 88))
        val exported = ledger.snapshot()
        assertEquals(88L, decodeModelUsageSnapshot(exported).totalInputTokens)
        ledger.record(delta("discard-on-import", 500))
        val imported = applyModelUsageDelta(null, delta("imported", 4).copy(conversationId = "restored"))
        ledger.replace(imported)
        assertEquals(imported, disk.value)
        assertNull(ledger.conversationFlow("owner").first())
        assertEquals(4L, ledger.conversationFlow("restored").first()!!.input)
        val restarted = store(disk)
        assertEquals(imported, restarted.snapshot())
        assertEquals(4L, restarted.conversationFlow("restored").first()!!.input)
    }

    @Test fun distinctRawFlowSuppressesOnlyEqualStringsAndKeepsChangesBackToEarlierValue() = runBlocking {
        val disk = Storage("{}")
        val ledger = store(disk)
        val values = Channel<String>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { ledger.rawFlow().collect { values.send(it) } }
        try {
            assertEquals("{}", withTimeout(5_000) { values.receive() })
            val first = applyModelUsageDelta(null, delta(input = 1))
            val second = applyModelUsageDelta(null, delta(input = 2))
            ledger.replace(first)
            assertEquals(first, withTimeout(5_000) { values.receive() })
            ledger.replace(first)
            ledger.replace(second)
            assertEquals(second, withTimeout(5_000) { values.receive() })
            ledger.replace(first)
            assertEquals(first, withTimeout(5_000) { values.receive() })
            assertTrue(values.tryReceive().isFailure)
        } finally { collector.cancelAndJoin(); values.close() }
    }
}
