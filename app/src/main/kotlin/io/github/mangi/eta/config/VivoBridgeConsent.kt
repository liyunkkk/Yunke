package io.github.mangi.eta.config

import android.content.SharedPreferences
import io.github.mangi.eta.EtaApp
import java.util.concurrent.CopyOnWriteArraySet

/** Two explicit stores; never use generic reconciliation that may resurrect a stale opt-in. */
internal object VivoBridgeConsent {
    private val session = VivoConsentSession()
    private val revocationListeners = CopyOnWriteArraySet<() -> Unit>()

    // A fresh Eta process never revives a stale persisted grant after a failed write.
    // The experimental switch must be explicitly confirmed again after process restart.
    fun localEnabled(): Boolean {
        val remote = Prefs.remotePreferencesForUi(EtaApp.serviceInstance)
        return effective(remote, Prefs.localAgentPreferences())
    }
    fun effective(remote: SharedPreferences?, local: SharedPreferences?) =
        session.allows(read(local), remote != null && read(remote))
    fun addRevocationListener(listener: () -> Unit) { revocationListeners.add(listener) }
    fun removeRevocationListener(listener: () -> Unit) { revocationListeners.remove(listener) }
    private fun revoke() {
        session.revoke()
        revocationListeners.forEach { listener -> runCatching { listener() } }
    }
    fun read(prefs: SharedPreferences?) = runCatching {
        prefs?.getBoolean(Prefs.Keys.VIVO_TEXT_BRIDGE, false) == true
    }.getOrDefault(false)

    fun commit(remote: SharedPreferences, local: SharedPreferences, enabled: Boolean): Boolean =
        commit(enabled,
            writeRemote = { put(remote, it) },
            writeLocal = { put(local, it) },
        )

    internal fun commit(enabled: Boolean, writeRemote: (Boolean) -> Boolean, writeLocal: (Boolean) -> Boolean): Boolean {
        fun remote(value: Boolean) = runCatching { writeRemote(value) }.getOrDefault(false)
        fun local(value: Boolean) = runCatching { writeLocal(value) }.getOrDefault(false)
        revoke() // Before any disk/Binder operation, including exceptions before memory changes.
        if (!enabled) {
            // Revoke model access before asking the framework to disable interception.
            val a = local(false)
            val b = remote(false)
            return a && b
        }
        if (!remote(true)) {
            local(false); remote(false)
            return false
        }
        if (!local(true)) {
            local(false); remote(false)
            return false
        }
        session.confirm(localWritten = true, remoteWritten = true)
        return true
    }

    private fun put(prefs: SharedPreferences, value: Boolean) =
        prefs.edit().putBoolean(Prefs.Keys.VIVO_TEXT_BRIDGE, value).commit()
}


/** Process-local confirmation is never reconstructed from disk. */
internal class VivoConsentSession {
    @Volatile private var confirmed = false
    fun confirm(localWritten: Boolean, remoteWritten: Boolean) { confirmed = localWritten && remoteWritten }
    fun revoke() { confirmed = false }
    fun allows(local: Boolean, remote: Boolean) = confirmed && local && remote
}
