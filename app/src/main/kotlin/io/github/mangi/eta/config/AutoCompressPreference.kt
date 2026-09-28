package io.github.mangi.eta.config

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app-wide automatic context compression switch. It is deliberately not scoped to an
 * assistant, conversation or sub-agent profile, and never falls back to remote preferences.
 * UI observers and execution-time checks read the same local key and default.
 */
internal class AutoCompressPreference(
    private val preferences: SharedPreferences? = Prefs.localAgentPreferences(),
) {
    val isWritable: Boolean get() = preferences != null

    // Do not cache the execution-time decision in a UI snapshot.
    val enabled: Boolean get() = read(preferences)

    fun setEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, enabled)?.apply()
    }

    /**
     * Publishes current storage values on the main thread, including external writes/removal/clear.
     * Register before the initial read so an update between composition and effect is not lost.
     * The returned subscription strongly owns the SharedPreferences listener and must be closed.
     */
    fun observe(onChanged: (Boolean) -> Unit): AutoCloseable {
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val refresh = Runnable {
            if (!closed.get()) onChanged(enabled)
        }
        fun refreshOnMain() {
            if (closed.get()) return
            if (Looper.myLooper() == main.looper) refresh.run() else main.post(refresh)
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED) refreshOnMain()
        }
        preferences?.registerOnSharedPreferenceChangeListener(listener)
        refreshOnMain()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                preferences?.unregisterOnSharedPreferenceChangeListener(listener)
                main.removeCallbacks(refresh)
            }
        }
    }

    companion object {
        fun read(preferences: SharedPreferences? = Prefs.localAgentPreferences()): Boolean {
            val default = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED)
            return preferences?.getBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, default) ?: default
        }
    }
}
