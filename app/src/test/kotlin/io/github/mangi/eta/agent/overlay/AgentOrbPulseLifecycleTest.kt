package io.github.mangi.eta.agent.overlay

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentOrbPulseLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tickingInvalidatesDrawWithoutRecomposingContent() {
        var compositions = 0
        lateinit var pulse: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent {
            pulse = rememberAgentOrbPulse(AgentOverlayPhase.RUNNING) { compose.mainClock.currentTime }
            SideEffect { compositions++ }
            Box(Modifier.size(56.dp).drawBehind { drawCircle(Color.Blue, alpha = pulse.value) })
        }
        compose.mainClock.advanceTimeBy(100)
        var baseline = 0
        var before = 0f
        compose.runOnIdle { baseline = compositions; before = pulse.value }
        compose.mainClock.advanceTimeBy(650)
        compose.runOnIdle {
            assertNotEquals(before, pulse.value)
            assertEquals("Drawing must not subscribe the composition to pulse values", baseline, compositions)
        }
    }

    @Test fun pausedFinishedAndFailedPhasesStopUpdating() {
        val phase = mutableStateOf(AgentOverlayPhase.RUNNING)
        lateinit var pulse: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent { pulse = rememberAgentOrbPulse(phase.value) { compose.mainClock.currentTime } }
        compose.mainClock.advanceTimeBy(300)
        for (quiet in listOf(AgentOverlayPhase.PAUSED, AgentOverlayPhase.FINISHED, AgentOverlayPhase.FAILED)) {
            compose.runOnIdle { phase.value = quiet }
            compose.mainClock.advanceTimeBy(100)
            lateinit var oldPulse: State<Float>
            var oldValue = 0f
            compose.runOnIdle { oldPulse = pulse; oldValue = pulse.value; assertEquals(ORB_STATIC_ALPHA, oldValue, 0f) }
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle { assertEquals(oldValue, oldPulse.value, 0f) }
        }
        compose.runOnIdle { phase.value = AgentOverlayPhase.RUNNING }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertTrue(pulse.value > 0.6f) }
    }

    @Test fun removingOrbCancelsItsPulseLoop() {
        val visible = mutableStateOf(true)
        lateinit var pulse: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent { if (visible.value) pulse = rememberAgentOrbPulse(AgentOverlayPhase.RUNNING) { compose.mainClock.currentTime } }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(100)
        var last = 0f
        compose.runOnIdle { last = pulse.value }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(last, pulse.value, 0f) }
    }
}
