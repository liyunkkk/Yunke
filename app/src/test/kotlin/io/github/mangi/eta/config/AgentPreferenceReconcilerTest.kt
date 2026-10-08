package io.github.mangi.eta.config

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class AgentPreferenceReconcilerTest {
    private val defaults = linkedMapOf("a" to false, "b" to true, "c" to false)

    @Test fun onlySuccessfullyConfirmedSameOwnerValuesSkipTheRepeat() {
        val bridge = AgentPreferenceReconciler(defaults)
        val owner = Any()
        val local = FakePrefs(mapOf("a" to true, "b" to false))
        val remote = FakePrefs(mapOf("a" to true, "b" to false, "unrelated" to true))
        bridge.reconcile(owner, local, remote)
        assertEquals(1, remote.commits)
        assertEquals(setOf("a", "b"), remote.written.last().keys)
        repeat(5) { bridge.reconcile(owner, local, remote) }
        assertEquals(1, remote.commits)
        local.map["b"] = true
        bridge.reconcile(owner, local, remote)
        assertEquals(2, remote.commits)
        assertEquals(mapOf("b" to true), remote.written.last())
        assertEquals(true, remote.durable["unrelated"])
        assertFalse(local.map.containsKey("c"))
        assertFalse(remote.map.containsKey("c"))
    }

    @Test fun failedMapMutationRetriesLatestLocalValueUntilConfirmed() {
        for (throws in listOf(false, true)) {
            val bridge = AgentPreferenceReconciler(defaults)
            val owner = Any()
            val local = FakePrefs(mapOf("a" to true, "b" to false))
            val remote = FakePrefs(mapOf("a" to false, "b" to false))
            bridge.reconcile(owner, local, remote)
            local.map["a"] = false
            remote.failures = 2
            remote.throwFailure = throws
            bridge.reconcile(owner, local, remote)
            assertEquals(false, remote.map["a"])
            assertEquals(true, remote.durable["a"])
            bridge.reconcile(owner, local, remote)
            assertEquals(3, remote.commits)
            assertEquals(mapOf("a" to false), remote.written.last())
            local.map["a"] = true
            bridge.reconcile(owner, local, remote)
            assertEquals(4, remote.commits)
            assertEquals(mapOf("a" to true), remote.written.last())
            assertEquals(true, remote.durable["a"])
            bridge.reconcile(owner, local, remote)
            assertEquals(4, remote.commits)
        }
    }

    @Test fun migrationOrderMissingDefaultsAndIdentityReplacementRemainAuthoritative() {
        val sequence = mutableListOf<String>()
        val bridge = AgentPreferenceReconciler(defaults)
        val owner = Any()
        val local = FakePrefs(mapOf("a" to true), "local", sequence)
        val remote = FakePrefs(mapOf("b" to false), "remote", sequence)
        bridge.reconcile(owner, local, remote)
        assertEquals(listOf("local", "remote"), sequence)
        assertEquals(false, local.map["b"])
        assertEquals(true, remote.durable["a"])
        assertFalse(local.map.containsKey("c"))
        bridge.reconcile(owner, local, remote) // migrated b now needs its first confirmation
        bridge.reconcile(owner, local, remote)
        assertEquals(2, remote.commits)
        val replacementOwner = Any()
        bridge.reconcile(replacementOwner, local, remote) // new service, same remote object
        assertEquals(3, remote.commits)
        val replacement = FakePrefs(remote.map)
        bridge.reconcile(replacementOwner, local, replacement)
        assertEquals(1, replacement.commits)
        replacement.map.remove("a")
        bridge.reconcile(replacementOwner, local, replacement)
        assertEquals(2, replacement.commits)
        assertEquals(mapOf("a" to true), replacement.written.last())
    }

    @Test fun failedLocalMigrationIsNotReportedAsPersistedAndRetries() {
        val bridge = AgentPreferenceReconciler(defaults)
        val owner = Any()
        val local = FakePrefs(emptyMap())
        val remote = FakePrefs(mapOf("a" to true))
        local.failures = 1
        bridge.reconcile(owner, local, remote)
        assertTrue(local.durable.isEmpty())
        assertEquals(0, remote.commits)
        // Same as original SharedPreferences failure semantics: in-process value is visible.
        assertEquals(true, local.map["a"])
        bridge.reconcile(owner, local, remote)
        assertEquals(1, remote.commits)
        assertEquals(true, remote.durable["a"])
    }

    private class FakePrefs(
        values: Map<String, Boolean>,
        private val name: String = "",
        private val sequence: MutableList<String> = mutableListOf(),
    ) : SharedPreferences {
        val map = values.toMutableMap()
        val durable = values.toMutableMap()
        var commits = 0
        var failures = 0
        var throwFailure = false
        val written = mutableListOf<Map<String, Boolean>>()
        override fun contains(key: String) = map.containsKey(key)
        override fun getBoolean(key: String, defValue: Boolean) = map[key] ?: defValue
        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String, defValue: String?) = defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = linkedMapOf<String, Boolean>()
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
            override fun commit(): Boolean {
                commits++; sequence += name; written += pending.toMap()
                map.putAll(pending) // faithful: map changes BEFORE binder failure
                if (failures > 0) {
                    failures--
                    if (throwFailure) throw IllegalStateException("simulated binder failure")
                    return false
                }
                durable.putAll(pending)
                return true
            }
            override fun apply() { commit() }
            override fun putString(key: String, value: String?) = this
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun remove(key: String) = this
            override fun clear() = this
        }
    }
}
