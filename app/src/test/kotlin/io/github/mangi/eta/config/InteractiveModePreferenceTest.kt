package io.github.mangi.eta.config

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class InteractiveModePreferenceTest {
    private val key = InteractiveModePreference.PREF_KEY
    private val surfaceKey = AgentTaskSurface.PREF_KEY

    private fun preferences(): TrackingPreferences = TrackingPreferences(
        RuntimeEnvironment.getApplication().getSharedPreferences(
            "interactive-mode-${UUID.randomUUID()}", Context.MODE_PRIVATE,
        ),
    )

    private fun drainMain() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun absentIndependentKeyDefaultsFalseForEveryLocationWithoutWritingOrMigrating() {
        AgentTaskSurfaceMode.entries.forEach { mode ->
            val prefs = preferences()
            prefs.edit().putString(surfaceKey, mode.wire).putString("unrelated", "preserved").commit()
            val before = prefs.all.toMap()
            val editsBefore = prefs.editCount
            val preference = InteractiveModePreference(prefs)
            assertFalse(preference.enabled)
            assertFalse(InteractiveModePreference.read(prefs))
            val observed = mutableListOf<Boolean>()
            val observation = preference.observe(observed::add)
            drainMain()
            assertEquals(listOf(false), observed)
            observation.close()
            assertEquals(before, prefs.all)
            assertEquals(editsBefore, prefs.editCount)
            assertFalse(prefs.contains(key))
            assertFalse(prefs.contains(AgentTaskSurface.ASK_MIGRATION_KEY))
        }
        val empty = preferences()
        val observation = InteractiveModePreference(empty).observe { assertFalse(it) }
        drainMain()
        observation.close()
        assertTrue(empty.all.isEmpty())
        assertEquals(0, empty.editCount)
    }

    @Test fun existingIndependentBooleanWinsForAllLocationsAndColdReadsAgreeWithObservers() {
        AgentTaskSurfaceMode.entries.forEach { mode ->
            listOf(false, true).forEach { enabled ->
                val prefs = preferences()
                prefs.edit().putString(surfaceKey, mode.wire).putBoolean(key, enabled).commit()
                val before = prefs.all.toMap()
                val editsBefore = prefs.editCount
                val preference = InteractiveModePreference(prefs)
                assertEquals(enabled, preference.enabled)
                assertEquals(enabled, InteractiveModePreference.read(prefs))
                assertEquals(enabled, InteractiveModePreference(prefs).enabled)
                val observed = mutableListOf<Boolean>()
                val observation = preference.observe(observed::add)
                drainMain()
                assertEquals(listOf(enabled), observed)
                observation.close()
                assertEquals(before, prefs.all)
                assertEquals(editsBefore, prefs.editCount)
            }
        }
    }

    @Test fun togglingOnAndOffNeverChangesForegroundBackgroundAskOrAMissingLocation() {
        (AgentTaskSurfaceMode.entries.map { it.wire } + listOf(null)).forEach { surface ->
            val prefs = preferences()
            val editor = prefs.edit().putString("unrelated", "preserved")
            if (surface != null) editor.putString(surfaceKey, surface)
            editor.commit()
            val otherValues = prefs.all.toMap()
            val preference = InteractiveModePreference(prefs)
            val observed = mutableListOf<Boolean>()
            val observation = preference.observe(observed::add)
            drainMain()
            listOf(true, false, true, false).forEach { enabled ->
                preference.setEnabled(enabled)
                drainMain()
                assertEquals(enabled, observed.last())
                assertEquals(enabled, InteractiveModePreference(prefs).enabled)
                assertEquals(enabled, InteractiveModePreference.read(prefs))
                assertEquals(otherValues, prefs.all.filterKeys { it != key })
            }
            assertEquals(listOf(false, true, false, true, false), observed)
            observation.close()
        }
    }

    @Test fun settingsLocationWritesDoNotChangeTheSwitchOrNotifyItsObservers() {
        listOf(false, true).forEach { enabled ->
            val prefs = preferences()
            prefs.edit().putBoolean(key, enabled).commit()
            val preference = InteractiveModePreference(prefs)
            val observed = mutableListOf<Boolean>()
            val observation = preference.observe(observed::add)
            drainMain()
            AgentTaskSurfaceMode.entries.forEach { mode ->
                prefs.edit().putString(surfaceKey, mode.wire).commit()
                drainMain()
                assertEquals(mode.wire, prefs.getString(surfaceKey, null))
                assertEquals(enabled, prefs.getBoolean(key, !enabled))
                assertEquals(enabled, preference.enabled)
                assertEquals(enabled, InteractiveModePreference.read(prefs))
                assertEquals(listOf(enabled), observed)
            }
            observation.close()
        }
    }

    @Test fun externalIndependentWritesRemovalAndClearRefreshTheSameColdRead() {
        val prefs = preferences()
        val observed = mutableListOf<Boolean>()
        val observation = InteractiveModePreference(prefs).observe(observed::add)
        drainMain()
        prefs.edit().putBoolean(key, true).commit()
        drainMain()
        assertTrue(observed.last())
        assertTrue(InteractiveModePreference.read(prefs))
        prefs.edit().remove(key).commit()
        drainMain()
        assertFalse(observed.last())
        assertFalse(InteractiveModePreference.read(prefs))
        prefs.edit().putBoolean(key, true).commit()
        drainMain()
        prefs.edit().clear().commit()
        prefs.notifyChanged(null) // Android R+ clear/restore can notify with a null key.
        drainMain()
        assertFalse(observed.last())
        assertFalse(InteractiveModePreference.read(prefs))
        observation.close()
    }

    @Test fun corruptBooleanAndUnavailableStorageFailClosedWithoutRepairWrites() {
        val prefs = preferences()
        prefs.edit().putString(surfaceKey, AgentTaskSurfaceMode.ASK.wire).putString(key, "not-a-boolean").commit()
        val before = prefs.all.toMap()
        val editsBefore = prefs.editCount
        val observed = mutableListOf<Boolean>()
        val preference = InteractiveModePreference(prefs)
        assertFalse(preference.enabled)
        assertFalse(InteractiveModePreference.read(prefs))
        val observation = preference.observe(observed::add)
        drainMain()
        assertEquals(listOf(false), observed)
        observation.close()
        assertEquals(before, prefs.all)
        assertEquals(editsBefore, prefs.editCount)
        // A deliberate user toggle may replace the bad bool, but never the location.
        preference.setEnabled(true)
        assertTrue(preference.enabled)
        assertEquals(AgentTaskSurfaceMode.ASK.wire, prefs.getString(surfaceKey, null))

        val unavailable = InteractiveModePreference(null)
        val missingValues = mutableListOf<Boolean>()
        val missingObservation = unavailable.observe(missingValues::add)
        unavailable.setEnabled(true)
        drainMain()
        assertFalse(unavailable.enabled)
        assertFalse(InteractiveModePreference.read(null))
        assertEquals(listOf(false), missingValues)
        missingObservation.close()
    }

    @Test fun backgroundNotificationsUseMainAndReadLatestStorageRatherThanCapturedValues() {
        val prefs = preferences()
        val observed = mutableListOf<Boolean>()
        val callbackLoopers = mutableListOf<Looper?>()
        val observation = InteractiveModePreference(prefs).observe {
            observed.add(it)
            callbackLoopers.add(Looper.myLooper())
        }
        drainMain()
        onWorker {
            prefs.edit().putBoolean(key, true).commit()
            prefs.notifyChanged(key)
            prefs.edit().putBoolean(key, false).commit()
            prefs.notifyChanged(key)
        }
        assertEquals(listOf(false), observed)
        drainMain()
        assertTrue(observed.all { !it })
        assertTrue(callbackLoopers.all { it == Looper.getMainLooper() })
        assertFalse(InteractiveModePreference.read(prefs))
        observation.close()
    }

    @Test fun registrationRereadsAndCloseUnregistersOnceSuppressingQueuedAndLateCallbacks() {
        val prefs = preferences()
        val preference = InteractiveModePreference(prefs)
        assertFalse(preference.enabled)
        prefs.afterRegister = { prefs.edit().putBoolean(key, true).commit() }
        val observed = mutableListOf<Boolean>()
        val observation = preference.observe(observed::add)
        drainMain()
        assertTrue(observed.last())
        assertEquals(1, prefs.listeners.size)
        val oldListener = prefs.listeners.single()
        onWorker { oldListener.onSharedPreferenceChanged(prefs, key) }
        observation.close()
        observation.close()
        assertEquals(0, prefs.listeners.size)
        assertEquals(1, prefs.unregisterCount)
        val before = observed.toList()
        prefs.edit().putBoolean(key, false).commit()
        onWorker { oldListener.onSharedPreferenceChanged(prefs, key) }
        drainMain()
        assertEquals(before, observed)
        prefs.afterRegister = null
        val reopened = mutableListOf<Boolean>()
        val nextObservation = InteractiveModePreference(prefs).observe(reopened::add)
        drainMain()
        assertEquals(listOf(false), reopened)
        nextObservation.close()
    }

    private fun onWorker(block: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { block() } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        worker.join()
        failure.get()?.let { throw it }
    }

    private class TrackingPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        var editCount = 0
        var unregisterCount = 0
        var afterRegister: (() -> Unit)? = null
        override fun edit(): SharedPreferences.Editor {
            editCount++
            return delegate.edit()
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
            delegate.registerOnSharedPreferenceChangeListener(listener)
            afterRegister?.invoke()
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            unregisterCount++
            listeners.remove(listener)
            delegate.unregisterOnSharedPreferenceChangeListener(listener)
        }
        fun notifyChanged(key: String?) {
            listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
        }
    }
}
