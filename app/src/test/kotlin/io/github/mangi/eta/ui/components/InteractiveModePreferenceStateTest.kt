package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.config.InteractiveModePreference
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class InteractiveModePreferenceStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun independentKeyUpdatesAndReentryReleaseListenersWithoutTouchingEitherLocation() {
        val first = preferences()
        val second = preferences()
        first.edit().putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.ASK.wire).commit()
        second.edit().putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.BACKGROUND.wire).commit()
        val selected = mutableStateOf(InteractiveModePreference(first))
        val visible = mutableStateOf(true)
        compose.setContent {
            if (visible.value) {
                val enabled by rememberInteractiveModeEnabled(selected.value)
                Text(if (enabled) "enabled" else "disabled")
            }
        }
        // ASK does not determine the switch, and composition must not initialize it.
        compose.onNodeWithText("disabled").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, first.listeners.size)
            assertFalse(first.contains(InteractiveModePreference.PREF_KEY))
            assertEquals(AgentTaskSurfaceMode.ASK.wire, first.getString(AgentTaskSurface.PREF_KEY, null))
            selected.value.setEnabled(true)
            assertTrue(InteractiveModePreference.read(first))
        }
        compose.onNodeWithText("enabled").assertIsDisplayed()
        // Reusing a composition slot with a different preference must release the old observer.
        compose.runOnIdle { selected.value = InteractiveModePreference(second) }
        compose.onNodeWithText("disabled").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, first.listeners.size)
            assertEquals(1, second.listeners.size)
            assertEquals(AgentTaskSurfaceMode.ASK.wire, first.getString(AgentTaskSurface.PREF_KEY, null))
            assertEquals(AgentTaskSurfaceMode.BACKGROUND.wire, second.getString(AgentTaskSurface.PREF_KEY, null))
            second.edit().putBoolean(InteractiveModePreference.PREF_KEY, true).commit()
        }
        compose.onNodeWithText("enabled").assertIsDisplayed()
        AgentTaskSurfaceMode.entries.forEach { mode ->
            compose.runOnIdle { second.edit().putString(AgentTaskSurface.PREF_KEY, mode.wire).commit() }
            compose.onNodeWithText("enabled").assertIsDisplayed()
            compose.runOnIdle { assertTrue(InteractiveModePreference.read(second)) }
        }
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("enabled").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, second.listeners.size)
            second.edit().putBoolean(InteractiveModePreference.PREF_KEY, false).commit()
        }
        compose.onNodeWithText("disabled").assertDoesNotExist()
        // Re-entry must cold-read the independent key rather than the disposed UI snapshot.
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("disabled").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, second.listeners.size)
            assertEquals(AgentTaskSurfaceMode.BACKGROUND.wire, second.getString(AgentTaskSurface.PREF_KEY, null))
            assertFalse(InteractiveModePreference(second).enabled)
            second.edit().putBoolean(InteractiveModePreference.PREF_KEY, true).commit()
        }
        compose.onNodeWithText("enabled").assertIsDisplayed()
        compose.runOnIdle { second.edit().remove(InteractiveModePreference.PREF_KEY).commit() }
        compose.onNodeWithText("disabled").assertIsDisplayed()
        compose.runOnIdle {
            // A bad stored type fails closed for both a cold read and an already-mounted observer.
            second.edit().putString(InteractiveModePreference.PREF_KEY, "invalid").commit()
            assertFalse(InteractiveModePreference.read(second))
        }
        compose.onNodeWithText("disabled").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle {
            assertEquals(0, second.listeners.size)
            assertEquals(2, second.unregisterCount)
            assertEquals(AgentTaskSurfaceMode.BACKGROUND.wire, second.getString(AgentTaskSurface.PREF_KEY, null))
        }
    }

    private fun preferences() = TrackingPreferences(
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "interactive-mode-compose-${UUID.randomUUID()}", Context.MODE_PRIVATE,
        ),
    )

    private class TrackingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        var unregisterCount = 0
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
            delegate.registerOnSharedPreferenceChangeListener(listener)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            unregisterCount++
            listeners.remove(listener)
            delegate.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
