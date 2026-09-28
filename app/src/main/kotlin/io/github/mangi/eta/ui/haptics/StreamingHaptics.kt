package io.github.mangi.eta.ui.haptics

import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Main-thread, frame-synchronous feedback. No network-event queue or delayed replay. */
internal object StreamingHaptics {
    private class Gate(val view: View, val lifecycle: Lifecycle, val enabled: () -> Boolean)

    /**
     * Several chat hosts observe concurrently (home and chat screens, conversation key changes).
     * A single slot would be cleared by whichever instance disposes last even though another live
     * instance still owns the visible view, silencing feedback for the rest of the process.
     */
    private val gates = mutableListOf<Gate>()

    /** Test-only counter of allowed advances; never consulted by production paths. */
    @Volatile internal var allowedAdvances: Long = 0
        private set

    fun onVisibleAdvance(view: View) {
        val allowed = synchronized(gates) {
            gates.any { gate ->
                gate.view === view && gate.enabled() &&
                    gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        // Window focus is intentionally not required: a dialog, notification shade or split-screen
        // peer takes focus while the streamed text stays visible in this same view.
        if (!allowed) return
        allowedAdvances++
        TouchHaptics.generationTick(view)
    }

    @Composable
    fun Observe(enabled: Boolean) {
        val view = LocalView.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val active by rememberUpdatedState(enabled)
        DisposableEffect(view, lifecycle) {
            val gate = Gate(view, lifecycle) { active }
            synchronized(gates) { gates += gate }
            onDispose { synchronized(gates) { gates.remove(gate) } }
        }
    }
}
