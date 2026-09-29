package io.github.mangi.eta.ui.haptics

import android.app.Activity
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    private var foregroundConversationId: String? = null

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
        synchronized(gates) { rememberForeground() }
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
        val view = currentConversationView(conversationId, allowBackground = true) ?: return
        TouchHaptics.onLiveToolActivity(view, toolId)
    }

    fun noteBackgroundOutput(graphemes: Int, conversationId: String? = null) {
        if (graphemes <= 0) return
        // 当前页可见时由打字机震动。只有这条会话在应用退到后台后才补震。
        val view = currentConversationView(conversationId, allowBackground = true) ?: return
        if (foregroundGate(view)) return
        allowedAdvances++
        pendingBackgroundTicks = (pendingBackgroundTicks + backgroundPulseCount(graphemes))
            .coerceAtMost(MAX_BACKGROUND_TICKS)
        backgroundView = view
        scheduleBackgroundTick()
    }

    private fun rememberForeground() {
        val resumed = gates.firstOrNull { gate ->
            !gate.conversationId.isNullOrBlank() && gate.enabled() &&
                gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        if (resumed != null) foregroundConversationId = resumed.conversationId
    }

    private fun currentConversationView(conversationId: String?, allowBackground: Boolean): View? {
        if (conversationId.isNullOrBlank()) return null
        return synchronized(gates) {
            rememberForeground()
            if (conversationId != foregroundConversationId) return@synchronized null
            gates.firstOrNull { gate ->
                gate.conversationId == conversationId && gate.enabled() &&
                    gate.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) &&
                    (gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                        (allowBackground && !activityResumed(gate.view)))
            }?.view
        }
    }

    private fun activityResumed(view: View): Boolean {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull() ?: return false
        val owner = activity as? LifecycleOwner ?: return false
        return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
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
                gate.conversationId != null && gate.conversationId == foregroundConversationId &&
                gate.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) &&
                !gate.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !activityResumed(view)
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
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && !conversationId.isNullOrBlank() && active) {
                    synchronized(gates) { foregroundConversationId = conversationId }
                }
            }
            synchronized(gates) {
                gates += gate
                if (!conversationId.isNullOrBlank() && active &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    foregroundConversationId = conversationId
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
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
