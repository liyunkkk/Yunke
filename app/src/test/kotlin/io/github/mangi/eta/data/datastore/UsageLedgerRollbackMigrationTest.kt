package io.github.mangi.eta.data.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsageLedgerRollbackMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun authoritativeFourMegabyteSourceIsCopiedVerbatimWithReceiptBeforeArchiveAndSurvivesRestart() = runBlocking {
        val raw = largeUsageLedger()
        assertTrue(raw.toByteArray().size >= 4_091_260)
        val source = File(temporary.root, "ledger.json").apply { writeText(raw) }
        val preferencesFile = File(temporary.root, "settings.preferences_pb")
        // An earlier Preferences value cannot win over the current committed independent file.
        val oldJob = SupervisorJob()
        val old = PreferenceDataStoreFactory.create(scope = CoroutineScope(oldJob + Dispatchers.IO)) { preferencesFile }
        old.edit { it[MODEL_USAGE_JSON] = "{\"old\":true}" }
        oldJob.cancelAndJoin()
        var archives = 0
        val migration = UsageLedgerRollbackMigration(source, archive = { file, digest ->
            archives++
            // No reentrant DataStore read in cleanup: inspect the already-written protobuf file.
            val persisted = preferencesFile.readBytes().toString(Charsets.UTF_8)
            assertTrue(persisted.contains(raw))
            assertTrue(persisted.contains("v1:sha256:$digest"))
            assertEquals(raw, file.readText())
            assertEquals(usageLedgerDigest(raw), digest)
            assertTrue(file.renameTo(File(temporary.root, "archived.json")))
        })
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(migrations = listOf(migration),
            scope = CoroutineScope(job + Dispatchers.IO)) { preferencesFile }
        try {
            val prefs = store.data.first()
            assertEquals(raw, prefs[MODEL_USAGE_JSON])
            assertEquals("v1:sha256:${usageLedgerDigest(raw)}", prefs[USAGE_ROLLBACK_RECEIPT])
            assertEquals(125, JSONObject(prefs[MODEL_USAGE_JSON]!!).getJSONObject("conversationTotalsV1").length())
            assertEquals(12, JSONObject(prefs[MODEL_USAGE_JSON]!!).getJSONObject("providers").length())
            assertTrue(JSONObject(prefs[MODEL_USAGE_JSON]!!).getBoolean("conversationTotalsInitialized"))
            assertEquals(9007199254740993L, PreferencesUsageLedger(store).conversationFlow("owner-0").first()!!.input)
        } finally { job.cancelAndJoin() }
        val restartJob = SupervisorJob()
        val restart = PreferenceDataStoreFactory.create(migrations = listOf(UsageLedgerRollbackMigration(source)),
            scope = CoroutineScope(restartJob + Dispatchers.IO)) { preferencesFile }
        try { assertEquals(raw, restart.data.first()[MODEL_USAGE_JSON]); assertEquals(1, archives) }
        finally { restartJob.cancelAndJoin() }
    }

    @Test fun missingSourceKeepsPreferencesAndOrphanPendingNeverWins() = runBlocking {
        val source = File(temporary.root, "absent.json")
        File(temporary.root, "absent.json.pending").writeText("{damaged")
        val prefs = emptyPreferences().toMutablePreferencesForTest().apply { this[MODEL_USAGE_JSON] = "{\"kept\":1}" }
        val migration = UsageLedgerRollbackMigration(source)
        val result = migration.migrate(prefs)
        assertEquals(prefs[MODEL_USAGE_JSON], result[MODEL_USAGE_JSON])
        assertEquals(USAGE_NO_SOURCE_RECEIPT, result[USAGE_ROLLBACK_RECEIPT])
        migration.cleanUp()
        source.writeText("{\"lateStale\":true}")
        assertEquals(result, UsageLedgerRollbackMigration(source).migrate(result))
    }

    @Test fun emptyAndWhitespaceUnusedSourcesAndEmptyJsonObjectsHaveExplicitReceipts() = runBlocking {
        for (raw in listOf("", " \n\t", "{}", """{
            "conversationTotalsInitialized":false,
            "conversationTotalsV1":{"owner":{"in":9007199254740993,"out":11,"k":7,"w":3}},
            "providers":{},"futureExtension":[null,{"保留":"  text  "},1.25e-7]
        }""")) {
            val source = File(temporary.root, "empty.json").apply { writeText(raw) }
            val migration = UsageLedgerRollbackMigration(source)
            val result = migration.migrate(emptyPreferences())
            assertEquals(raw, result[MODEL_USAGE_JSON])
            assertEquals("v1:sha256:${usageLedgerDigest(raw)}", result[USAGE_ROLLBACK_RECEIPT])
            assertTrue(source.exists()) // migrate itself must never retire the source.
            migration.cleanUp()
            assertFalse(source.exists())
        }
    }

    @Test fun corruptTruncatedInvalidUtf8AndConflictingEmptySourcesFailWithoutPublishingEmptyStats() = runBlocking {
        for (bytes in listOf("{truncated".toByteArray(), "[]".toByteArray(), "{} trailing".toByteArray(),
            byteArrayOf(0x7b, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x7d), "".toByteArray())) {
            val source = File(temporary.root, "bad.json").apply { writeBytes(bytes) }
            val original = emptyPreferences().toMutablePreferencesForTest().apply { this[MODEL_USAGE_JSON] = "{\"kept\":42}" }
            val migration = UsageLedgerRollbackMigration(source)
            try { migration.migrate(original); fail("must propagate corruption") } catch (_: IOException) { }
            assertEquals("{\"kept\":42}", original[MODEL_USAGE_JSON])
            assertNull(original[USAGE_ROLLBACK_RECEIPT])
            assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test fun unreadableSourceFailsFirstAccessThenRetriesRatherThanFallingBack() = runBlocking {
        val source = File(temporary.root, "ledger.json").apply { mkdir() } // Stable read failure even as root.
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(migrations = listOf(UsageLedgerRollbackMigration(source)),
            scope = CoroutineScope(job + Dispatchers.IO)) { File(temporary.root, "retry.preferences_pb") }
        try {
            try { PreferencesUsageLedger(store).snapshot(); fail("must not return empty") } catch (_: IOException) { }
            assertTrue(source.delete())
            source.writeText("{\"recovered\":7}")
            assertEquals("{\"recovered\":7}", PreferencesUsageLedger(store).snapshot())
            assertNotNull(store.data.first()[USAGE_ROLLBACK_RECEIPT])
        } finally { job.cancelAndJoin() }
    }

    @Test fun archiveFailureChangedAndCorruptSurvivingSourceCannotOverwriteNewPreferences() = runBlocking {
        val source = File(temporary.root, "ledger.json").apply { writeText("{\"source\":1}") }
        val errors = mutableListOf<Throwable>()
        val initial = UsageLedgerRollbackMigration(source, archive = { _, _ -> throw IOException("archive failed") },
            reportArchiveFailure = { errors += it })
        var prefs = initial.migrate(emptyPreferences())
        initial.cleanUp()
        assertEquals(1, errors.size)
        val receipt = prefs[USAGE_ROLLBACK_RECEIPT]
        for (raw in listOf("{\"new\":99}", "", " \n")) {
            prefs = prefs.toMutablePreferencesForTest().apply { this[MODEL_USAGE_JSON] = raw }
            source.writeText("{corrupt surviving source")
            val repeated = UsageLedgerRollbackMigration(source, reportArchiveFailure = { errors += it })
            assertEquals(prefs, repeated.migrate(prefs))
            repeated.cleanUp()
            assertTrue(source.exists())
            assertEquals(receipt, prefs[USAGE_ROLLBACK_RECEIPT])
            assertEquals(raw, prefs[MODEL_USAGE_JSON])
        }
        assertEquals(4, errors.size)
    }

    // A faulting Serializer exercises DataStore's real migrate -> commit -> cleanUp ordering.
    // Only string keys are needed here; production migration above uses the real Preferences serializer.
    private class StringsSerializer(var failWrites: Boolean = false) : Serializer<Preferences> {
        override val defaultValue: Preferences = emptyPreferences()
        override suspend fun readFrom(input: InputStream): Preferences {
            val root = JSONObject(input.readBytes().toString(Charsets.UTF_8))
            return emptyPreferences().toMutablePreferencesForTest().apply {
                root.keys().forEach { key -> this[stringPreferencesKey(key)] = root.getString(key) }
            }
        }
        override suspend fun writeTo(t: Preferences, output: OutputStream) {
            if (failWrites) throw IOException("injected durable write failure")
            val root = JSONObject()
            t.asMap().forEach { (key, value) -> root.put(key.name, value) }
            output.write(root.toString().toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun failedDataStoreCommitPersistsNeitherLedgerNorReceiptAndNeverArchivesSource() = runBlocking {
        val file = File(temporary.root, "atomic.data")
        val oldJob = SupervisorJob()
        val old = DataStoreFactory.create(serializer = StringsSerializer(), scope = CoroutineScope(oldJob + Dispatchers.IO)) { file }
        old.edit { it[MODEL_USAGE_JSON] = "{\"old\":42}" }
        oldJob.cancelAndJoin()
        val source = File(temporary.root, "ledger.json").apply { writeText("{\"authoritative\":99}") }
        var archives = 0
        val migration = UsageLedgerRollbackMigration(source, archive = { _, _ -> archives++ })
        val failedJob = SupervisorJob()
        val failed = DataStoreFactory.create(serializer = StringsSerializer(true), migrations = listOf(migration),
            scope = CoroutineScope(failedJob + Dispatchers.IO)) { file }
        try {
            try { failed.data.first(); fail("must fail initialization") } catch (_: IOException) { }
            assertEquals(0, archives)
            assertEquals("{\"authoritative\":99}", source.readText())
        } finally { failedJob.cancelAndJoin() }
        val inspectJob = SupervisorJob()
        val inspect = DataStoreFactory.create(serializer = StringsSerializer(), scope = CoroutineScope(inspectJob + Dispatchers.IO)) { file }
        try {
            assertEquals("{\"old\":42}", inspect.data.first()[MODEL_USAGE_JSON])
            assertNull(inspect.data.first()[USAGE_ROLLBACK_RECEIPT])
        } finally { inspectJob.cancelAndJoin() }
        val retryJob = SupervisorJob()
        val retry = DataStoreFactory.create(serializer = StringsSerializer(), migrations = listOf(migration),
            scope = CoroutineScope(retryJob + Dispatchers.IO)) { file }
        try {
            assertEquals("{\"authoritative\":99}", retry.data.first()[MODEL_USAGE_JSON])
            assertNotNull(retry.data.first()[USAGE_ROLLBACK_RECEIPT])
            assertEquals(1, archives)
        } finally { retryJob.cancelAndJoin() }
    }
}

private fun Preferences.toMutablePreferencesForTest() = toMutablePreferences()
