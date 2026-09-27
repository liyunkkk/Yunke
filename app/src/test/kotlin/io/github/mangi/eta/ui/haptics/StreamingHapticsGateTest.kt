package io.github.mangi.eta.ui.haptics

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: streaming feedback must survive a second concurrent observer and must not require
 * window focus. A dialog or the notification shade takes focus while the text stays visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class StreamingHapticsGateTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.CREATED }
    }

    /** Counts ticks by observing whether the gate allowed the advance for this view. */
    private fun ticked(block: () -> Unit): Boolean {
        val before = StreamingHaptics.allowedAdvances
        block()
        return StreamingHaptics.allowedAdvances > before
    }

    @Test
    fun secondObserverDisposalKeepsFeedbackForTheRemainingHost() {
        val owner = Owner().apply { resume() }
        var showSecond by mutableStateOf(true)
        lateinit var view: android.view.View
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                view = LocalView.current
                StreamingHaptics.Observe(enabled = true)
                if (showSecond) StreamingHaptics.Observe(enabled = true)
            }
        }
        compose.runOnIdle { assertTrue(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
        // The later observer disposes; the first one still owns the visible view.
        showSecond = false
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
    }

    @Test
    fun lostWindowFocusStillTicksButStoppedLifecycleDoesNot() {
        val owner = Owner().apply { resume() }
        lateinit var view: android.view.View
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                view = LocalView.current
                StreamingHaptics.Observe(enabled = true)
            }
        }
        // Robolectric views report no window focus; feedback must not depend on it.
        compose.runOnIdle {
            assertFalse(view.hasWindowFocus())
            assertTrue(ticked { StreamingHaptics.onVisibleAdvance(view) })
        }
        compose.runOnIdle { owner.stop() }
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
    }

    @Test
    fun disabledObserverAndUnknownViewNeverTick() {
        val owner = Owner().apply { resume() }
        lateinit var view: android.view.View
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                view = LocalView.current
                StreamingHaptics.Observe(enabled = false)
            }
        }
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.onVisibleAdvance(view) }) }
        val other = android.view.View(org.robolectric.RuntimeEnvironment.getApplication())
        compose.runOnIdle { assertFalse(ticked { StreamingHaptics.onVisibleAdvance(other) }) }
    }
}
