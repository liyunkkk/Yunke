package io.github.mangi.eta.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class ErrorReconnectSettingsTest {
    @Test
    fun setterAndLegacyUpdatesPreserveAllSettingsFields() = runBlocking {
        val before = SettingsDataStore.settings()
        try {
            SettingsDataStore.updateSettings {
                it.copy(selectedProviderId = "provider", selectedModelId = "model", memoryEnabled = false)
            }
            ErrorReconnectPolicy.entries.forEach { policy ->
                SettingsDataStore.setErrorReconnectPolicy(policy)
                assertEquals(policy, SettingsDataStore.errorReconnectPolicyFlow().first())
                val actual = SettingsDataStore.settings()
                assertEquals("provider", actual.selectedProviderId)
                assertEquals("model", actual.selectedModelId)
                assertEquals(false, actual.memoryEnabled)
                assertEquals(before.appearance, actual.appearance)
                assertEquals(before.fileLoggingEnabled, actual.fileLoggingEnabled)
            }
            SettingsDataStore.setErrorReconnectPolicy(ErrorReconnectPolicy.WINDOW_5M)
            SettingsDataStore.setMemoryEnabled(true)
            SettingsDataStore.setFileLoggingEnabled(false)
            SettingsDataStore.setSelectedModelId("other-model")
            SettingsDataStore.setAppearanceSettings(before.appearance.copy(blurEnabled = false))
            assertEquals(ErrorReconnectPolicy.WINDOW_5M, SettingsDataStore.settings().errorReconnectPolicy)
        } finally {
            SettingsDataStore.updateSettings { before }
        }
    }

    @Test
    fun missingPreferencesDefaultToContinuousButUnknownPreferencesFailClosed() = runBlocking {
        val before = SettingsDataStore.settings()
        val key = stringPreferencesKey("error_reconnect_policy")
        // Exercise the actual Preferences decoder without adding a production test-only API.
        val field = SettingsDataStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val store = field.get(SettingsDataStore) as DataStore<Preferences>
        try {
            store.edit { it.remove(key) }
            assertEquals(ErrorReconnectPolicy.CONTINUOUS, SettingsDataStore.errorReconnectPolicyFlow().first())
            store.edit { it[key] = "future_policy" }
            assertEquals(ErrorReconnectPolicy.NONE, SettingsDataStore.settings().errorReconnectPolicy)
            SettingsDataStore.setMemoryEnabled(false)
            assertEquals("future_policy", store.data.first()[key])
        } finally {
            SettingsDataStore.updateSettings { before }
        }
    }

    @Test
    fun unrelatedUpdatesAndBackupPreserveFutureSchemaUntilExplicitChoice() = runBlocking {
        val before = SettingsDataStore.backupSnapshot()
        val field = SettingsDataStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val store = field.get(SettingsDataStore) as DataStore<Preferences>
        try {
            store.edit {
                it[ERROR_RECONNECT_POLICY] = "continuous"
                it[ERROR_RECONNECT_POLICY_VERSION] = ErrorReconnectPolicy.STORAGE_VERSION + 1
            }
            assertEquals(ErrorReconnectPolicy.NONE, SettingsDataStore.settings().errorReconnectPolicy)
            SettingsDataStore.setMemoryEnabled(false)
            val persisted = store.data.first()
            assertEquals("continuous", persisted[ERROR_RECONNECT_POLICY])
            assertEquals(2, persisted[ERROR_RECONNECT_POLICY_VERSION])
            val backup = SettingsDataStore.backupSnapshot()
            assertEquals("continuous", backup.errorReconnectPolicy)
            assertEquals(2, backup.errorReconnectPolicyVersion)
            try { SettingsDataStore.restoreBackup(backup); org.junit.Assert.fail("future schema") }
            catch (_: IllegalArgumentException) { }
            SettingsDataStore.setErrorReconnectPolicy(ErrorReconnectPolicy.NONE)
            assertEquals("none", store.data.first()[ERROR_RECONNECT_POLICY])
            assertEquals(1, store.data.first()[ERROR_RECONNECT_POLICY_VERSION])
        } finally { SettingsDataStore.restoreBackup(before) }
    }

    @Test
    fun backupRoundTripIncludesEveryPolicy() = runBlocking {
        val before = SettingsDataStore.backupSnapshot()
        try {
            ErrorReconnectPolicy.entries.forEach { policy ->
                SettingsDataStore.setErrorReconnectPolicy(policy)
                val backup = SettingsDataStore.backupSnapshot()
                assertEquals(policy.persistedValue, backup.errorReconnectPolicy)
                assertEquals(1, backup.errorReconnectPolicyVersion)
                val decoded = Json.decodeFromString<EtaSettingsBackup>(Json.encodeToString(backup))
                SettingsDataStore.setErrorReconnectPolicy(ErrorReconnectPolicy.NONE)
                SettingsDataStore.restoreBackup(decoded)
                assertEquals(policy, SettingsDataStore.errorReconnectPolicyFlow().first())
            }
        } finally {
            SettingsDataStore.restoreBackup(before)
        }
    }

    @Test
    fun futureBackupVersionDoesNotAlterCurrentSettings() = runBlocking {
        val before = SettingsDataStore.backupSnapshot()
        try {
            SettingsDataStore.restoreBackup(before.copy(errorReconnectPolicyVersion = 2))
            org.junit.Assert.fail("future version must not be applied")
        } catch (_: IllegalArgumentException) {
            assertEquals(before, SettingsDataStore.backupSnapshot())
        }
    }

    @Test
    fun oldDefaultsUpgradeButExplicitNewNoneAndUnknownBackupsStayOff() = runBlocking {
        val before = SettingsDataStore.backupSnapshot()
        val compatibleBackups = listOf(
            Json.decodeFromString<EtaSettingsBackup>("{}") to ErrorReconnectPolicy.CONTINUOUS,
            Json.decodeFromString<EtaSettingsBackup>("""{"errorReconnectPolicy":null}""") to ErrorReconnectPolicy.CONTINUOUS,
            Json.decodeFromString<EtaSettingsBackup>("""{"errorReconnectPolicy":"none"}""") to ErrorReconnectPolicy.CONTINUOUS,
            Json.decodeFromString<EtaSettingsBackup>("""{"errorReconnectPolicy":"none","errorReconnectPolicyVersion":1}""") to ErrorReconnectPolicy.NONE,
            Json.decodeFromString<EtaSettingsBackup>("""{"errorReconnectPolicy":"future_policy"}""") to ErrorReconnectPolicy.NONE,
        )
        try {
            compatibleBackups.forEach { (backup, expected) ->
                SettingsDataStore.setErrorReconnectPolicy(ErrorReconnectPolicy.CONTINUOUS)
                SettingsDataStore.restoreBackup(backup)
                assertEquals(expected, SettingsDataStore.settings().errorReconnectPolicy)
                assertEquals(expected.persistedValue, SettingsDataStore.backupSnapshot().errorReconnectPolicy)
                assertEquals(1, SettingsDataStore.backupSnapshot().errorReconnectPolicyVersion)
            }
        } finally {
            SettingsDataStore.restoreBackup(before)
        }
    }
}
