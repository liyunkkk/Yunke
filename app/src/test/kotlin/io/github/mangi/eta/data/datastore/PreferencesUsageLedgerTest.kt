package io.github.mangi.eta.data.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import io.github.mangi.eta.data.repository.ConversationUsageTotals
import io.github.mangi.eta.data.repository.applyModelUsageDelta
import io.github.mangi.eta.data.repository.conversationUsageTotals
import io.github.mangi.eta.data.repository.decodeModelUsageSnapshot
import io.github.mangi.eta.data.repository.seedConversationUsage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferencesUsageLedgerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun everyPartialIsDurableBeforeReturnAndMatchesSequentialSemantics() = runBlocking {
        val store = FaultPreferencesStore()
        val ledger = PreferencesUsageLedger(store)
        var expected = ""
        repeat(20) { index ->
            val delta = usageDelta(input = index + 1L).copy(
                conversationId = if (index % 2 == 0) "owner" else "alternate",
                day = java.time.LocalDate.of(2026, 1, index + 1),
            )
            expected = applyModelUsageDelta(expected, delta)
            ledger.record(delta)
            assertEquals(expected, store.committed.value[MODEL_USAGE_JSON])
        }
        assertEquals(20, store.attempts) // No timer/count batching, even mid-request.
        assertEquals(conversationUsageTotals(expected, "owner"), ledger.conversationFlow("owner").first())
        val model = decodeModelUsageSnapshot(ledger.snapshot()).providers.single().models.single()
        assertEquals(20, model.activeDays)
        assertEquals(2, model.conversationCount)
    }

    @Test fun requestReplacementCanDecreaseTotalsAndDoesNotChargeDuplicateReceipt() = runBlocking {
        val store = FaultPreferencesStore()
        val ledger = PreferencesUsageLedger(store)
        ledger.record(usageDelta("first", 900))
        ledger.record(usageDelta("second", 300))
        ledger.record(usageDelta("first", 20).copy(outputTokens = 2, cachedTokens = 1, cacheCreationTokens = 0))
        val partial = store.committed.value[MODEL_USAGE_JSON]
        ledger.record(usageDelta("first", 20).copy(outputTokens = 2, cachedTokens = 1, cacheCreationTokens = 0))
        assertEquals(partial, store.committed.value[MODEL_USAGE_JSON])
        assertEquals(ConversationUsageTotals(320, 12, 31, 5), ledger.conversationFlow("owner").first())
    }

    @Test fun concurrentRequestsCannotLoseReplacements() = runBlocking {
        val store = FaultPreferencesStore()
        val ledger = PreferencesUsageLedger(store)
        coroutineScope { repeat(100) { index -> launch(Dispatchers.Default) {
            ledger.record(usageDelta("request-$index", 100))
            ledger.record(usageDelta("request-$index", 2))
        } } }
        val model = decodeModelUsageSnapshot(ledger.snapshot()).providers.single().models.single()
        assertEquals(200L, model.inputTokens)
        assertEquals(100, model.events.size)
        assertEquals(200, store.attempts)
    }

    @Test fun commitFailureKeepsOldSnapshotAndProjectionAndRetryIsExactlyOnce() = runBlocking {
        val store = FaultPreferencesStore()
        val ledger = PreferencesUsageLedger(store)
        ledger.record(usageDelta("old", 11))
        val old = ledger.snapshot()
        store.failures = 1
        try { ledger.record(usageDelta("new", 71)); fail("must report failure") } catch (_: IOException) { }
        assertEquals(old, ledger.snapshot())
        assertEquals(11L, ledger.conversationFlow("owner").first()!!.input)
        ledger.record(usageDelta("new", 71))
        ledger.record(usageDelta("new", 71))
        assertEquals(82L, decodeModelUsageSnapshot(ledger.snapshot()).totalInputTokens)
    }

    @Test fun cancellationDuringCommitCannotDiscardAnAlreadyReceivedPartial() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = FaultPreferencesStore().apply { beforeCommit = { entered.complete(Unit); release.await() } }
        val ledger = PreferencesUsageLedger(store)
        val request = launch { ledger.record(usageDelta(input = 23)) }
        entered.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertEquals(23L, decodeModelUsageSnapshot(ledger.snapshot()).totalInputTokens)
    }

    @Test fun malformedCurrentOrReplacementCannotSilentlyReplaceSavedHistory() = runBlocking {
        for (raw in listOf("{truncated", "[]", "{} trailing", "{\"providers\":7}",
            """{"providers":{"p":{"models":{"m":{"events":"damaged"}}}}}""",
            """{"conversationTotalsV1":{"owner":{"in":"damaged"}}}""")) {
            val store = FaultPreferencesStore()
            store.edit { it[MODEL_USAGE_JSON] = raw }
            val ledger = PreferencesUsageLedger(store)
            try { ledger.snapshot(); fail("must reject corruption") } catch (_: IOException) { }
            try { ledger.record(usageDelta()); fail("must preserve corruption") } catch (_: IOException) { }
            assertEquals(raw, store.committed.value[MODEL_USAGE_JSON])
        }
        val store = FaultPreferencesStore()
        val ledger = PreferencesUsageLedger(store)
        ledger.record(usageDelta())
        val old = ledger.snapshot()
        try { ledger.replace("[]"); fail("invalid replacement") } catch (_: IOException) { }
        assertEquals(old, ledger.snapshot())
    }

    @Test fun snapshotReplacementAndEmptyResetInvalidateTheBoundedProjection() = runBlocking {
        val ledger = PreferencesUsageLedger(FaultPreferencesStore())
        ledger.record(usageDelta(input = 88))
        val exported = ledger.snapshot()
        val imported = applyModelUsageDelta(null, usageDelta("imported", 4).copy(conversationId = "restored"))
        ledger.replace(imported)
        assertNull(ledger.conversationFlow("owner").first())
        assertEquals(4L, ledger.conversationFlow("restored").first()!!.input)
        ledger.replace("")
        assertNull(ledger.conversationFlow("restored").first())
        ledger.replace(exported)
        assertEquals(88L, ledger.conversationFlow("owner").first()!!.input)
    }

    @Test fun rawFlowDeduplicatesEqualStringsButKeepsChangesBackToAnEarlierValue() = runBlocking {
        val store = FaultPreferencesStore()
        val ledger = PreferencesUsageLedger(store)
        ledger.replace("{}")
        val values = Channel<String>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { ledger.rawFlow().collect { values.send(it) } }
        try {
            assertEquals("{}", withTimeout(5000) { values.receive() })
            val first = applyModelUsageDelta(null, usageDelta(input = 1))
            val second = applyModelUsageDelta(null, usageDelta(input = 2))
            ledger.replace(first)
            assertEquals(first, withTimeout(5000) { values.receive() })
            ledger.replace(first)
            delay(20)
            assertTrue(values.tryReceive().isFailure)
            ledger.replace(second)
            assertEquals(second, withTimeout(5000) { values.receive() })
            ledger.replace(first)
            assertEquals(first, withTimeout(5000) { values.receive() })
        } finally { collector.cancelAndJoin(); values.close() }
    }

    @Test fun realPreferencesReloadNeedsNoFlushAndRepeatedLegacySeedingKeepsLargeLifetimeTotals() = runBlocking {
        val file = File(temporary.root, "usage.preferences_pb")
        val raw = largeUsageLedger()
        val firstJob = SupervisorJob()
        val first = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO)) { file }
        val ledger = PreferencesUsageLedger(first)
        try {
            ledger.replace(raw)
            val legacy = (0 until 125).associate { "owner-$it" to ConversationUsageTotals(1, 1, 1, 1) }
            repeat(2) { ledger.update { seedConversationUsage(it, legacy) } }
            assertEquals(9007199254740993L, ledger.conversationFlow("owner-0").first()!!.input)
            ledger.record(usageDelta("new", 9).copy(providerId = "provider-0", modelId = "model", conversationId = "owner-0"))
        } finally { firstJob.cancelAndJoin() }
        val secondJob = SupervisorJob()
        val restarted = PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO)) { file }
        try {
            val restored = PreferencesUsageLedger(restarted)
            assertEquals(9007199254741002L, restored.conversationFlow("owner-0").first()!!.input)
            assertEquals(12, decodeModelUsageSnapshot(restored.snapshot()).providers.size)
            assertNotNull(restarted.data.first()[USAGE_ROLLBACK_RECEIPT])
        } finally { secondJob.cancelAndJoin() }
    }
}
