package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.mangi.eta.config.AutoCompressPreference
import io.github.mangi.eta.config.Prefs
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
class AutoCompressPreferenceStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compositionTracksItsPreferenceAndReentryReleasesTheOldListener() {
        val first = preferences()
        val second = preferences()
        val selected = mutableStateOf(AutoCompressPreference(first))
        val visible = mutableStateOf(true)
        var displayed = false
        compose.setContent {
            if (visible.value) {
                val enabled by rememberAutoCompressEnabled(selected.value)
                SideEffect { displayed = enabled }
            }
        }
        compose.runOnIdle {
            assertFalse(displayed)
            assertEquals(1, first.listeners.size)
            selected.value.setEnabled(true)
        }
        compose.runOnIdle {
            assertTrue(displayed)
            // Reusing the same composition slot with a different source must not reuse its state.
            selected.value = AutoCompressPreference(second)
        }
        compose.runOnIdle {
            assertFalse(displayed)
            assertEquals(0, first.listeners.size)
            assertEquals(1, second.listeners.size)
            second.edit().putBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, true).apply()
        }
        compose.runOnIdle {
            assertTrue(displayed)
            visible.value = false
        }
        compose.runOnIdle {
            assertEquals(0, second.listeners.size)
            second.edit().putBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, false).apply()
        }
        compose.runOnIdle {
            assertTrue(displayed) // There is no disposed UI callback.
            visible.value = true
        }
        compose.runOnIdle {
            assertFalse(displayed)
            assertEquals(1, second.listeners.size)
            visible.value = false
        }
        compose.runOnIdle { assertEquals(0, second.listeners.size) }
    }

    private fun preferences() = TrackingPreferences(
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "auto-compress-compose-${UUID.randomUUID()}", Context.MODE_PRIVATE,
        ),
    )

    private class TrackingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners.add(listener)
            delegate.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners.remove(listener)
            delegate.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
