package io.github.mangi.eta.ui.haptics

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Main-thread, frame-synchronous feedback. No network-event queue or delayed replay. */
internal object StreamingHaptics {
    private class Gate(
        val view: View,
        val lifecycle: Lifecycle,
        val conversationId: String?,
        val enabled: () -> Boolean,
    )

    /**
     * Several chat hosts observe concurrently (home and chat screens, conversation key changes).
     * A single slot would be cleared by whichever instance disposes last even though another live
     * instance still owns the visible view, silencing feedback for the rest of the process.
     */
    private val gates = mutableListOf<Gate>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingBackgroundTicks = 0
    private var backgroundTickScheduled = false
    private var backgroundView: View? = null

    /** Test-only counter of allowed advances; never consulted by production paths. */
    @Volatile internal var allowedAdvances: Long = 0
        private set

    private val backgroundTick = object : Runnable {
        override fun run() {
            backgroundTickScheduled = false
            val view = backgroundView
            if (view == null || pendingBackgroundTicks <= 0 || !backgroundGate(view)) {
                pendingBackgroundTicks = 0
                backgroundView = null
                return
            }
            pendingBackgroundTicks--
            TouchHaptics.generationTick(view)
            if (pendingBackgroundTicks > 0) scheduleBackgroundTick()
        }
    }

    fun onVisibleAdvance(view: View) {
        if (!foregroundGate(view)) return
        allowedAdvances++
        TouchHaptics.generationTick(view)
    }

    /**
     * Text and tool steps keep arriving after the activity stops, but the reveal clock does not.
     * Pulse the same generation tick directly so leaving the app does not cut the vibration.
     */
    /** 前台也走这条：工具标签只出现一次，不能等界面刚好在 32ms 的打字间隔里把这次丢掉。 */
    fun noteToolAppeared(toolId: String, conversationId: String? = null) {
        if (toolId.isBlank()) return
        val view = resumedView(conversationId) ?: return
        TouchHaptics.onLiveToolActivity(view, toolId)
    }

    fun noteBackgroundOutput(graphemes: Int, conversationId: String? = null) {
        // 离开当前聊天页后不再补震。当前页的正文由可见打字机负责。
        if (graphemes <= 0 || resumedView(conversationId) == null) return
    }

    private fun resumedView(conversationId: String?): View? {
        if (conversationId.isNullOrBlank()) return null
        return synchronized(gates) {
            gates.firstOrNull { gate ->
                gate.conversationId == conversationId && gate.enabled() &&
                    gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }?.view
        }
    }

    private fun scheduleBackgroundTick() {
        if (backgroundTickScheduled) return
        backgroundTickScheduled = true
        mainHandler.postDelayed(backgroundTick, BACKGROUND_TICK_INTERVAL_MS)
    }

    private fun foregroundGate(view: View): Boolean = synchronized(gates) {
        gates.any { gate ->
            gate.view === view && gate.enabled() &&
                gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }

    private fun backgroundGate(view: View): Boolean = synchronized(gates) {
        gates.any { gate ->
            gate.view === view && gate.enabled() &&
                gate.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) &&
                !gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }

    private fun cancelBackgroundTicks() {
        pendingBackgroundTicks = 0
        backgroundView = null
        backgroundTickScheduled = false
        mainHandler.removeCallbacks(backgroundTick)
    }

    @Composable
    fun Observe(enabled: Boolean, conversationId: String? = null) {
        val view = LocalView.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val active by rememberUpdatedState(enabled)
        DisposableEffect(view, lifecycle, conversationId) {
            val gate = Gate(view, lifecycle, conversationId) { active }
            synchronized(gates) { gates += gate }
            onDispose {
                synchronized(gates) { gates.remove(gate) }
                if (synchronized(gates) { gates.isEmpty() }) cancelBackgroundTicks()
            }
        }
    }

    private const val BACKGROUND_TICK_INTERVAL_MS = 32L
    private const val MAX_BACKGROUND_TICKS = 36
}

/** One light tick per grapheme of hidden output, capped so a large chunk cannot buzz for long. */
internal fun backgroundPulseCount(graphemes: Int): Int = graphemes.coerceIn(1, 36)
