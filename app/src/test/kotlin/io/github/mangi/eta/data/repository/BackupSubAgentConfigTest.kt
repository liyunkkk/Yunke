package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupSubAgentConfigTest {
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, String>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "commit" -> true
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "contains" -> values.containsKey(args!![0] as String)
                "getString" -> values[args!![0] as String] ?: args[1]
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error("Unexpected preference method ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun oldArchiveDoesNotReadCurrentSelectionOrLiveSeed() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        val active = SubAgentConfigKey.Conversation("active")
        store.update(active) { it.copy(enabled = false, profiles = emptyList(), diagnosticsEnabled = true) }
        val archive = BackupSubAgentConfig.archiveForImport(2, null, store)
        val target = SubAgentConfigKey.Conversation("new")
        assertTrue(store.importOwner(target, archive))
        assertTrue(store.snapshot(target).enabled)
        assertFalse(store.snapshot(target).diagnosticsEnabled)
        assertEquals(listOf("legacy-0", "legacy-2", "legacy-3", "legacy-1"), store.snapshot(target).profiles.map { it.id })
        assertTrue(store.snapshot(active).profiles.isEmpty())
    }

    @Test fun newOwnerMappingPreservesFullConfigAndNeverOverwritesCollision() {
        val prefs = preferences()
        val store = ConversationSubAgentPreferences(prefs)
        val source = SubAgentConfigKey.Conversation("old")
        val model = SubAgentParallelModel("provider", "api-model")
        store.update(source) { it.copy(enabled = false, diagnosticsEnabled = true,
            parallelLimits = mapOf(model to 4)) }
        val archive = BackupSubAgentConfig.archiveForExport("old", store)
        val mapped = BackupSubAgentConfig.archiveForImport(3, archive, store)
        assertFalse(BackupSubAgentConfig.hasOwner(prefs, "new"))
        assertTrue(store.importOwner(SubAgentConfigKey.Conversation("new"), mapped))
        assertEquals(store.snapshot(source), store.snapshot(SubAgentConfigKey.Conversation("new")))
        assertEquals(4, store.snapshot(SubAgentConfigKey.Conversation("new")).parallelLimits[model])
        assertTrue(BackupSubAgentConfig.hasOwner(prefs, "new"))
        assertFalse(store.importOwner(SubAgentConfigKey.Conversation("new"), mapped))
        BackupSubAgentConfig.removeImportedOwner(prefs, "new")
        assertFalse(BackupSubAgentConfig.hasOwner(prefs, "new"))
        assertTrue(BackupSubAgentConfig.hasOwner(prefs, "old"))
    }

    @Test fun rejectsCorruptionAndUnsupportedVersionsBeforeReplacement() {
        val store = ConversationSubAgentPreferences(preferences())
        val valid = BackupSubAgentConfig.archiveForImport(2, null, store)
        val bad = JSONObject(valid).put("version", 987).toString()
        assertTrue(runCatching { BackupSubAgentConfig.archiveForImport(3, bad, store) }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_seed_v1" to "s:{invalid"), store) }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_owner_v1_c_YQ" to "b:true"), store) }.isFailure)
        assertTrue(runCatching { BackupSubAgentConfig.validatePreferences(mapOf(
            "agent_conversation_child_owner_v1_c_YQ" to "s:$bad"), store) }.isFailure)
        BackupSubAgentConfig.validatePreferences(mapOf("unrelated" to "s:anything"), store)
    }
}
