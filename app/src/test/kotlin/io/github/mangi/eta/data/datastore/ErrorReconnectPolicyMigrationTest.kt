package io.github.mangi.eta.data.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
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

class ErrorReconnectPolicyMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyDefaultsUpgradeAndSelectedTimeoutsStayUnchanged() = runBlocking {
        val migration = ErrorReconnectPolicyMigration()
        for (raw in listOf(null, "none", "window_30s", "window_1m", "window_5m", "continuous", "future_policy")) {
            val old = emptyPreferences().toMutablePreferences().apply {
                if (raw != null) this[ERROR_RECONNECT_POLICY] = raw
                this[stringPreferencesKey("unrelated")] = "preserved"
            }
            val result = migration.migrate(old)
            val expected = if (raw == null || raw == "none") "continuous" else raw
            assertEquals(expected, result[ERROR_RECONNECT_POLICY])
            assertEquals(1, result[ERROR_RECONNECT_POLICY_VERSION])
            assertEquals("preserved", result[stringPreferencesKey("unrelated")])
            assertNull(old[ERROR_RECONNECT_POLICY_VERSION])
            assertFalse(migration.shouldMigrate(result))
            assertEquals(result, migration.migrate(result))
        }
    }

    @Test fun committedMigrationAndExplicitOffSurviveRestart() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        val seedJob = SupervisorJob()
        val seed = PreferenceDataStoreFactory.create(scope = CoroutineScope(seedJob + Dispatchers.IO)) { file }
        seed.edit { it[ERROR_RECONNECT_POLICY] = "none" }
        seedJob.cancelAndJoin()
        val firstJob = SupervisorJob()
        val first = PreferenceDataStoreFactory.create(migrations = listOf(ErrorReconnectPolicyMigration()),
            scope = CoroutineScope(firstJob + Dispatchers.IO)) { file }
        try {
            assertEquals("continuous", first.data.first()[ERROR_RECONNECT_POLICY])
            first.edit { it[ERROR_RECONNECT_POLICY] = "none" }
        } finally { firstJob.cancelAndJoin() }
        val restartJob = SupervisorJob()
        val restarted = PreferenceDataStoreFactory.create(migrations = listOf(ErrorReconnectPolicyMigration()),
            scope = CoroutineScope(restartJob + Dispatchers.IO)) { file }
        try {
            assertEquals("none", restarted.data.first()[ERROR_RECONNECT_POLICY])
            assertEquals(1, restarted.data.first()[ERROR_RECONNECT_POLICY_VERSION])
        } finally { restartJob.cancelAndJoin() }
    }

    private class JsonPreferencesSerializer(private val failWrites: Boolean = false) : Serializer<Preferences> {
        override val defaultValue: Preferences = emptyPreferences()
        override suspend fun readFrom(input: InputStream): Preferences {
            val json = JSONObject(input.readBytes().toString(Charsets.UTF_8))
            return emptyPreferences().toMutablePreferences().apply {
                json.keys().forEach { key ->
                    val value = json.get(key)
                    if (value is Number) this[intPreferencesKey(key)] = value.toInt()
                    else this[stringPreferencesKey(key)] = value.toString()
                }
            }
        }
        override suspend fun writeTo(t: Preferences, output: OutputStream) {
            if (failWrites) throw IOException("injected commit failure")
            val json = JSONObject()
            t.asMap().forEach { (key, value) -> json.put(key.name, value) }
            output.write(json.toString().toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun failedCommitDoesNotPersistPolicyOrVersionAndCanRetry() = runBlocking {
        val file = File(temporary.root, "atomic.data").apply { writeText("{\"error_reconnect_policy\":\"none\"}") }
        val before = file.readBytes()
        val failedJob = SupervisorJob()
        val failed = DataStoreFactory.create(serializer = JsonPreferencesSerializer(true),
            migrations = listOf(ErrorReconnectPolicyMigration()), scope = CoroutineScope(failedJob + Dispatchers.IO)) { file }
        try {
            try { failed.data.first(); fail("commit must fail") } catch (_: IOException) { }
            assertArrayEquals(before, file.readBytes())
        } finally { failedJob.cancelAndJoin() }
        val retryJob = SupervisorJob()
        val retry = DataStoreFactory.create(serializer = JsonPreferencesSerializer(),
            migrations = listOf(ErrorReconnectPolicyMigration()), scope = CoroutineScope(retryJob + Dispatchers.IO)) { file }
        try {
            assertEquals("continuous", retry.data.first()[ERROR_RECONNECT_POLICY])
            assertEquals(1, retry.data.first()[ERROR_RECONNECT_POLICY_VERSION])
        } finally { retryJob.cancelAndJoin() }
    }

    @Test fun failingLedgerMigrationDoesNotPartiallyCommitReconnectMigration() = runBlocking {
        val file = File(temporary.root, "joint.data").apply { writeText("{\"error_reconnect_policy\":\"none\"}") }
        val before = file.readBytes()
        val ledger = File(temporary.root, "ledger.json").apply { writeText("{damaged") }
        val failedJob = SupervisorJob()
        val failed = DataStoreFactory.create(serializer = JsonPreferencesSerializer(), migrations = listOf(
            ErrorReconnectPolicyMigration(), UsageLedgerRollbackMigration(ledger, archive = { _, _ -> }),
        ), scope = CoroutineScope(failedJob + Dispatchers.IO)) { file }
        try {
            try { failed.data.first(); fail("invalid ledger must block initialization") } catch (_: IOException) { }
            assertArrayEquals(before, file.readBytes())
        } finally { failedJob.cancelAndJoin() }
        ledger.writeText("{}")
        val retryJob = SupervisorJob()
        val retry = DataStoreFactory.create(serializer = JsonPreferencesSerializer(), migrations = listOf(
            ErrorReconnectPolicyMigration(), UsageLedgerRollbackMigration(ledger, archive = { _, _ -> }),
        ), scope = CoroutineScope(retryJob + Dispatchers.IO)) { file }
        try {
            assertEquals("continuous", retry.data.first()[ERROR_RECONNECT_POLICY])
            assertEquals(1, retry.data.first()[ERROR_RECONNECT_POLICY_VERSION])
            assertEquals("{}", retry.data.first()[MODEL_USAGE_JSON])
        } finally { retryJob.cancelAndJoin() }
    }
}
