package io.github.mangi.eta.data.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mangi.eta.data.model.ErrorReconnectPolicy

internal val ERROR_RECONNECT_POLICY = stringPreferencesKey("error_reconnect_policy")
internal val ERROR_RECONNECT_POLICY_VERSION = intPreferencesKey("error_reconnect_policy_version")

/** One durable transaction upgrades the legacy default and records the new choice semantics. */
internal class ErrorReconnectPolicyMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[ERROR_RECONNECT_POLICY_VERSION] ?: 0) < ErrorReconnectPolicy.STORAGE_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (!shouldMigrate(currentData)) return currentData
        val policy = ErrorReconnectPolicy.fromStoredSettings(
            currentData[ERROR_RECONNECT_POLICY], currentData[ERROR_RECONNECT_POLICY_VERSION],
        )
        return currentData.toMutablePreferences().apply {
            // Preserve future/unknown enum strings without enabling them.
            this[ERROR_RECONNECT_POLICY] = currentData[ERROR_RECONNECT_POLICY]
                ?.takeIf { raw -> ErrorReconnectPolicy.entries.none { it.persistedValue == raw } }
                ?: policy.persistedValue
            this[ERROR_RECONNECT_POLICY_VERSION] = ErrorReconnectPolicy.STORAGE_VERSION
        }
    }

    override suspend fun cleanUp() = Unit
}
