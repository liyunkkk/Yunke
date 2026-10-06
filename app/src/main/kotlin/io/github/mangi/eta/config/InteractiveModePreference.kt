package io.github.mangi.eta.config

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Independent local interaction switch; never infer or migrate the execution location. */
internal class InteractiveModePreference(
    private val preferences: SharedPreferences? = Prefs.localAgentPreferences(),
) {
    val enabled: Boolean get() = read(preferences)
    fun setEnabled(enabled: Boolean) {
        preferences?.edit()?.putBoolean(PREF_KEY, enabled)?.apply()
    }

    fun observe(onChanged: (Boolean) -> Unit): AutoCloseable {
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val refresh = Runnable { if (!closed.get()) onChanged(enabled) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (!closed.get() && (key == null || key == PREF_KEY)) {
                if (Looper.myLooper() == main.looper) refresh.run() else main.post(refresh)
            }
        }
        preferences?.registerOnSharedPreferenceChangeListener(listener)
        if (Looper.myLooper() == main.looper) refresh.run() else main.post(refresh)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                preferences?.unregisterOnSharedPreferenceChangeListener(listener)
                main.removeCallbacks(refresh)
            }
        }
    }
    companion object {
        const val PREF_KEY = "interactive_mode"
        fun read(preferences: SharedPreferences? = Prefs.localAgentPreferences()): Boolean =
            runCatching { preferences?.getBoolean(PREF_KEY, false) ?: false }.getOrDefault(false)
    }
}
