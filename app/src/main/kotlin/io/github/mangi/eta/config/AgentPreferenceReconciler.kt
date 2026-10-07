package io.github.mangi.eta.config

import android.content.SharedPreferences
import java.lang.ref.WeakReference

/**
 * Synchronous, local-authoritative migration, exactly as before. Skip only keys
 * whose value this bridge successfully committed for the SAME service and prefs
 * identities. A failed commit can update the remote in-process map, so equality
 * alone is NOT evidence of persistence. Never store a failed value for replay.
 */
internal class AgentPreferenceReconciler(private val defaults: Map<String, Boolean>) {
    private var owner = WeakReference<Any>(null)
    private var preferences = WeakReference<SharedPreferences>(null)
    private val confirmed = mutableMapOf<String, Boolean>()

    @Synchronized
    fun reconcile(serviceIdentity: Any, local: SharedPreferences, remote: SharedPreferences) {
        if (owner.get() !== serviceIdentity || preferences.get() !== remote) {
            confirmed.clear()
            owner = WeakReference(serviceIdentity)
            preferences = WeakReference(remote)
        }
        val localEditor = local.edit()
        val remoteEditor = remote.edit()
        var updateLocal = false
        val writes = linkedMapOf<String, Boolean>()
        defaults.forEach { (key, default) ->
            when {
                local.contains(key) -> {
                    val current = local.getBoolean(key, default)
                    if (confirmed[key] != current || !remote.contains(key) ||
                        remote.getBoolean(key, default) != current
                    ) {
                        remoteEditor.putBoolean(key, current)
                        writes[key] = current
                    }
                }
                remote.contains(key) -> {
                    localEditor.putBoolean(key, remote.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        // Preserve migration-before-remote ordering and the existing failure policy.
        if (updateLocal) localEditor.commit()
        if (writes.isNotEmpty()) {
            // Remove confirmation BEFORE commit, including a throw after updating its map.
            writes.keys.forEach(confirmed::remove)
            if (runCatching { remoteEditor.commit() }.getOrDefault(false)) {
                confirmed.putAll(writes)
            }
        }
    }
}
