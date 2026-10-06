package io.github.mangi.eta.data.datastore

import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.decodeModelUsageSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Integration coverage for the actual Preferences-derived flows, in addition to the JVM store tests. */
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36])
class SettingsLedgerIsolationTest {
    @Test fun independentWritesAndDistinctProjectionsKeepAllRealChanges() = runBlocking {
        SettingsDataStore.init(RuntimeEnvironment.getApplication())
        val baseline = SettingsDataStore.backupSnapshot()
        SettingsDataStore.addModelUsage("{}")
        val settings = Channel<Settings>(Channel.UNLIMITED)
        val selected = Channel<String?>(Channel.UNLIMITED)
        val usage = Channel<String>(Channel.UNLIMITED)
        val jobs = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) { SettingsDataStore.settingsFlow().collect { settings.send(it) } },
            launch(start = CoroutineStart.UNDISPATCHED) { SettingsDataStore.selectedModelIdFlow().collect { selected.send(it) } },
            launch(start = CoroutineStart.UNDISPATCHED) { SettingsDataStore.modelUsageFlow().collect { usage.send(it) } },
        )
        try {
            withTimeout(5_000) { settings.receive(); selected.receive() }
            assertEquals("{}", withTimeout(5_000) { usage.receive() })
            SettingsDataStore.setSelectedModelId("isolation-a")
            assertEquals("isolation-a", withTimeout(5_000) { settings.receive() }.selectedModelId)
            assertEquals("isolation-a", withTimeout(5_000) { selected.receive() })
            SettingsDataStore.incrementLaunchCount() // Not represented in Settings or selected model.
            SettingsDataStore.recordModelUsage(ModelUsageDelta(
                "test-provider", "Test", "test-model", "Test", 12, 3,
                conversationId = "test-owner", requestId = "one-request", atMillis = 1000,
            ))
            SettingsDataStore.flushModelUsage()
            assertEquals(12L, decodeModelUsageSnapshot(withTimeout(5_000) { usage.receive() }).totalInputTokens)
            delay(50)
            assertTrue(settings.tryReceive().isFailure)
            assertTrue(selected.tryReceive().isFailure)
            assertTrue(usage.tryReceive().isFailure)
            SettingsDataStore.setMemoryEnabled(!SettingsDataStore.settings().memoryEnabled)
            withTimeout(5_000) { settings.receive() }
            for (id in listOf("isolation-b", "isolation-a")) {
                SettingsDataStore.setSelectedModelId(id)
                assertEquals(id, withTimeout(5_000) { settings.receive() }.selectedModelId)
                assertEquals(id, withTimeout(5_000) { selected.receive() })
            }
            delay(50)
            assertTrue(selected.tryReceive().isFailure)
            assertTrue(usage.tryReceive().isFailure)
            // Backup includes the independent ledger; import replaces it and its cached totals.
            val exported = SettingsDataStore.backupSnapshot()
            SettingsDataStore.addModelUsage("{}")
            SettingsDataStore.restoreBackup(exported)
            assertEquals(exported.modelUsageJson, SettingsDataStore.modelUsageJson())
            assertEquals(12L, SettingsDataStore.conversationUsageFlow("test-owner").first()!!.input)
        } finally {
            jobs.forEach { it.cancelAndJoin() }
            settings.close(); selected.close(); usage.close()
            SettingsDataStore.restoreBackup(baseline)
        }
    }
}
