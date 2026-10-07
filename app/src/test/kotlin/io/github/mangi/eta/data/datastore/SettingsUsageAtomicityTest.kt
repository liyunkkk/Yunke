package io.github.mangi.eta.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import io.github.mangi.eta.data.repository.applyModelUsageDelta
import io.github.mangi.eta.data.repository.decodeModelUsageSnapshot
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsUsageAtomicityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun richBackup(input: Long, model: String) = EtaSettingsBackup(
        selectedProviderId = "provider", selectedModelId = model, memoryEnabled = false,
        fileLoggingEnabled = true, linuxDistribution = "ubuntu", linuxBackends = mapOf("ubuntu" to "proot"),
        selectedModelByProvider = mapOf("provider" to model),
        errorReconnectPolicy = ErrorReconnectPolicy.CONTINUOUS.persistedValue,
        errorReconnectPolicyVersion = ErrorReconnectPolicy.STORAGE_VERSION,
        modelUsageJson = applyModelUsageDelta(null, usageDelta(model, input)),
        retiredInputTokens = input + 1, retiredOutputTokens = input + 2, retiredCachedTokens = input + 3,
        retiredConversations = 5, retiredMessages = 9, retiredHeatmapJson = "{\"2026-01-01\":5}",
    )

    @Test fun actualRestoreIsOneEditAndCommitFailureChangesNeitherSettingsNorAnyStatistics() = runBlocking {
        val store = FaultPreferencesStore()
        withSettingsStore(store) {
            SettingsDataStore.restoreBackup(richBackup(11, "old").copy(
                errorReconnectPolicy = ErrorReconnectPolicy.NONE.persistedValue,
            ))
            val before = store.committed.value
            val beforeSnapshot = SettingsDataStore.backupSnapshot()
            val receipt = before[USAGE_ROLLBACK_RECEIPT]
            val attempts = store.attempts
            store.failures = 1
            try { SettingsDataStore.restoreBackup(richBackup(99, "new")); fail("must propagate commit failure") }
            catch (_: IOException) { }
            assertEquals(attempts + 1, store.attempts) // ONE transform contains ledger + retired + settings.
            assertEquals(before.asMap(), store.committed.value.asMap())
            assertEquals(beforeSnapshot, SettingsDataStore.backupSnapshot())
            assertEquals(11L, SettingsDataStore.conversationUsageFlow("owner").first()!!.input)
            SettingsDataStore.restoreBackup(richBackup(99, "new"))
            assertEquals(attempts + 2, store.attempts)
            assertEquals(richBackup(99, "new"), SettingsDataStore.backupSnapshot())
            assertEquals(receipt, store.committed.value[USAGE_ROLLBACK_RECEIPT])
        }
    }

    @Test fun backupSnapshotReadsOnlyOneCommittedPreferencesVersion() = runBlocking {
        val store = FaultPreferencesStore()
        withSettingsStore(store) {
            SettingsDataStore.restoreBackup(richBackup(17, "same-version"))
            val reads = store.reads
            val snapshot = SettingsDataStore.backupSnapshot()
            assertEquals(reads + 1, store.reads)
            assertEquals(17L, decodeModelUsageSnapshot(snapshot.modelUsageJson).totalInputTokens)
            assertEquals("same-version", snapshot.selectedModelId)
        }
    }

    @Test fun missingBackupStatisticsPreserveAllExistingFieldsWhileExplicitZerosAndEmptyResetThem() = runBlocking {
        val store = FaultPreferencesStore()
        withSettingsStore(store) {
            val original = richBackup(37, "old")
            SettingsDataStore.restoreBackup(original)
            val partial = Json.decodeFromString<EtaSettingsBackup>("{\"selectedModelId\":\"partial\"}")
            assertNull(partial.modelUsageJson)
            assertNull(partial.retiredInputTokens)
            assertNull(partial.retiredHeatmapJson)
            SettingsDataStore.restoreBackup(partial)
            val preserved = SettingsDataStore.backupSnapshot()
            assertEquals(original.modelUsageJson, preserved.modelUsageJson)
            assertEquals(original.retiredInputTokens, preserved.retiredInputTokens)
            assertEquals(original.retiredOutputTokens, preserved.retiredOutputTokens)
            assertEquals(original.retiredCachedTokens, preserved.retiredCachedTokens)
            assertEquals(original.retiredConversations, preserved.retiredConversations)
            assertEquals(original.retiredMessages, preserved.retiredMessages)
            assertEquals(original.retiredHeatmapJson, preserved.retiredHeatmapJson)
            val receipt = store.committed.value[USAGE_ROLLBACK_RECEIPT]
            SettingsDataStore.clearRetiredUsage()
            assertEquals(original.modelUsageJson, SettingsDataStore.modelUsageJson())
            assertEquals(receipt, store.committed.value[USAGE_ROLLBACK_RECEIPT])
            assertEquals(0L, SettingsDataStore.backupSnapshot().retiredInputTokens)
            val reset = Json.decodeFromString<EtaSettingsBackup>("""{"modelUsageJson":"",
                "retiredInputTokens":0,"retiredOutputTokens":0,"retiredCachedTokens":0,
                "retiredConversations":0,"retiredMessages":0,"retiredHeatmapJson":""}""")
            SettingsDataStore.restoreBackup(reset)
            assertEquals("", store.committed.value[MODEL_USAGE_JSON]) // Explicit key, NOT absence.
            assertEquals(0L, SettingsDataStore.backupSnapshot().retiredInputTokens)
            assertEquals("", SettingsDataStore.backupSnapshot().retiredHeatmapJson)
            assertNotNull(store.committed.value[USAGE_ROLLBACK_RECEIPT])
        }
    }

    @Test fun migrationThenActualRestoreOrResetAndRestartNeverReimportsSurvivingChangedOrCorruptSource() = runBlocking {
        for ((index, restored) in listOf(richBackup(41, "restored"), richBackup(0, "empty").copy(modelUsageJson = ""),
            richBackup(0, "blank").copy(modelUsageJson = " \n\t")).withIndex()) {
            val folder = temporary.newFolder("case-$index")
            val source = File(folder, "ledger.json").apply { writeText(applyModelUsageDelta(null, usageDelta(input = 800))) }
            val file = File(folder, "usage.preferences_pb")
            val errors = mutableListOf<Throwable>()
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(migrations = listOf(UsageLedgerRollbackMigration(source,
                archive = { _, _ -> throw IOException("archive unavailable") }, reportArchiveFailure = { errors += it })),
                scope = CoroutineScope(job + Dispatchers.IO)) { file }
            var receipt: String? = null
            try {
                withSettingsStore(store) {
                    assertEquals(800L, decodeModelUsageSnapshot(SettingsDataStore.modelUsageJson()).totalInputTokens)
                    receipt = store.data.first()[USAGE_ROLLBACK_RECEIPT]
                    SettingsDataStore.restoreBackup(restored)
                    assertEquals(restored, SettingsDataStore.backupSnapshot())
                    assertEquals(receipt, store.data.first()[USAGE_ROLLBACK_RECEIPT])
                }
            } finally { job.cancelAndJoin() }
            assertTrue(source.exists())
            // Both a changed valid source and a damaged one must be fenced permanently.
            source.writeText(if (index == 0) "{\"stale\":true}" else "{damaged")
            val restartJob = SupervisorJob()
            val restarted = PreferenceDataStoreFactory.create(migrations = listOf(UsageLedgerRollbackMigration(source,
                reportArchiveFailure = { errors += it })), scope = CoroutineScope(restartJob + Dispatchers.IO)) { file }
            try {
                withSettingsStore(restarted) {
                    assertEquals(restored, SettingsDataStore.backupSnapshot())
                    assertEquals(restored.modelUsageJson, SettingsDataStore.modelUsageJson())
                    assertEquals(receipt, restarted.data.first()[USAGE_ROLLBACK_RECEIPT])
                    // initializeConversationUsage's update path cannot resurrect old source bytes.
                    SettingsDataStore.updateModelUsage { io.github.mangi.eta.data.repository.seedConversationUsage(it, emptyMap()) }
                    assertEquals(if (index == 0) 41L else 0L,
                        decodeModelUsageSnapshot(SettingsDataStore.modelUsageJson()).totalInputTokens)
                }
                assertTrue(source.exists())
                assertEquals(2, errors.size)
            } finally { restartJob.cancelAndJoin() }
        }
    }

    @Test fun readAndMigrationFailureCannotAppearAsDefaultSettingsZeroRetiredUsageOrEmptyBackup() = runBlocking {
        val failed = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = kotlinx.coroutines.flow.flow { throw IOException("migration failed") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("migration failed")
        }
        withSettingsStore(failed) {
            val operations: List<suspend () -> Any?> = listOf(
                { SettingsDataStore.settings() }, { SettingsDataStore.modelUsageJson() },
                { SettingsDataStore.modelUsageFlow().first() }, { SettingsDataStore.conversationUsageFlow("owner").first() },
                { SettingsDataStore.retiredUsage() }, { SettingsDataStore.launchCount() },
                { SettingsDataStore.backupSnapshot() }, { SettingsDataStore.recordModelUsage(usageDelta()) },
                { SettingsDataStore.updateModelUsage { "{}" } }, { SettingsDataStore.restoreBackup(richBackup(9, "new")) },
                { SettingsDataStore.clearRetiredUsage() },
            )
            for (operation in operations) {
                try { operation(); fail("must propagate, not default") } catch (_: IOException) { }
            }
        }
    }
}
