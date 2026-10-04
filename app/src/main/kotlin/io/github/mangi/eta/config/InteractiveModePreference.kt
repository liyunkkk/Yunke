package io.github.mangi.eta.config

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import java.util.concurrent.atomic.AtomicBoolean

/** App-wide switch for asking where phone UI actions should run before the first action. */
internal class InteractiveModePreference(
    private val preferences: SharedPreferences? = Prefs.localAgentPreferences(),
) {
    val enabled: Boolean get() = AgentTaskSurface.stored() == AgentTaskSurfaceMode.ASK

    fun setEnabled(enabled: Boolean) {
        AgentTaskSurface.save(if (enabled) AgentTaskSurfaceMode.ASK else AgentTaskSurfaceMode.FOREGROUND)
    }

    fun observe(onChanged: (Boolean) -> Unit): AutoCloseable {
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val refresh = Runnable { if (!closed.get()) onChanged(enabled) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == AgentTaskSurface.PREF_KEY) {
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
}
