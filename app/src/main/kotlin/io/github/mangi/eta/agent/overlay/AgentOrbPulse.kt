package io.github.mangi.eta.agent.overlay

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos

internal const val ORB_PULSE_INTERVAL_MS = 50L
private const val ORB_PULSE_PERIOD_MS = 2800L
internal const val ORB_STATIC_ALPHA = 0.85f

/** 0.6..1.0 breathing, independent of display refresh rate. */
internal fun agentOrbPulseAlpha(elapsedMillis: Long): Float {
    val fraction = (elapsedMillis.coerceAtLeast(0L) % ORB_PULSE_PERIOD_MS).toDouble() / ORB_PULSE_PERIOD_MS
    return (0.8 - 0.2 * cos(2.0 * PI * fraction)).toFloat()
}

/** No perpetual display-frame subscription; leaving composition or pausing cancels the timer. */
@Composable
internal fun rememberAgentOrbPulse(
    phase: AgentOverlayPhase,
    nowMillis: () -> Long = SystemClock::uptimeMillis,
): State<Float> {
    val pulse = remember(phase) {
        mutableFloatStateOf(if (phase == AgentOverlayPhase.RUNNING) agentOrbPulseAlpha(0) else ORB_STATIC_ALPHA)
    }
    LaunchedEffect(phase) {
        if (phase != AgentOverlayPhase.RUNNING) return@LaunchedEffect
        val start = nowMillis()
        while (isActive) {
            delay(ORB_PULSE_INTERVAL_MS)
            pulse.floatValue = agentOrbPulseAlpha(nowMillis() - start)
        }
    }
    return pulse
}
