package io.github.mangi.eta.config

import android.content.SharedPreferences

/** Two explicit stores; never use generic reconciliation that may resurrect a stale opt-in. */
internal object VivoBridgeConsent {
    fun localEnabled() = read(Prefs.localAgentPreferences())
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
        return true
    }

    private fun put(prefs: SharedPreferences, value: Boolean) =
        prefs.edit().putBoolean(Prefs.Keys.VIVO_TEXT_BRIDGE, value).commit()
}
