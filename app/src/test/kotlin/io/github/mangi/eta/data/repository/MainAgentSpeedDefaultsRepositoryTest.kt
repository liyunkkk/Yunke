package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainAgentSpeedDefaultsRepositoryTest {
    private val a = Model("selection-a", "gpt-5", "A")
    private val b = Model("selection-b", "gpt-5", "B with the same API alias")
    private fun provider(id: String = "p") = OpenAiCompatibleProviderSetting(id, "Relay", "https://example.invalid",
        models = listOf(a, b))
    private fun preferences(): SharedPreferences = (RuntimeEnvironment.getApplication() as Context).getSharedPreferences(
        "main-speed-test", Context.MODE_PRIVATE,
    ).also { it.edit().clear().commit() }

    @Test fun providerAndSelectionIsolationIncludingIdenticalApiAliases() {
        val prefs = preferences()
        val repository = MainAgentSpeedDefaultsRepository(prefs)
        val p = provider(); val q = provider("q")
        assertEquals(GptSpeedMode.NORMAL, repository.modeFor(p.id, a.id))
        assertTrue(repository.remember(p, a, GptSpeedMode.FAST))
        assertEquals(GptSpeedMode.NORMAL, repository.modeFor(p.id, b.id))
        assertEquals(GptSpeedMode.NORMAL, repository.modeFor(q.id, a.id))
        repository.remember(p, b, GptSpeedMode.ULTRA_FAST)
        repository.remember(q, a, GptSpeedMode.NORMAL)
        val restarted = MainAgentSpeedDefaultsRepository(prefs)
        assertEquals(GptSpeedMode.FAST, restarted.modeFor(p.id, a.id))
        assertEquals(GptSpeedMode.ULTRA_FAST, restarted.modeFor(p.id, b.id))
        assertEquals(GptSpeedMode.NORMAL, restarted.modeFor(q.id, a.id))
        assertEquals(GptSpeedMode.NORMAL, restarted.modeFor(p.id, a.modelId)) // API alias is NOT a key.
        // Renaming the display/API label does not silently create a different identity.
        val renamed = a.copy(modelId = "namespace/gpt-6", displayName = "Renamed")
        restarted.remember(p.copy(models = listOf(renamed, b)), renamed, GptSpeedMode.ULTRA_FAST)
        assertEquals(GptSpeedMode.ULTRA_FAST, restarted.modeFor(p.id, a.id))
        assertEquals(GptSpeedMode.ULTRA_FAST, restarted.modeFor(p.id, b.id))
    }

    @Test fun nonGptMissingDisabledAndDuplicateBindingsCannotWriteOrEraseGptMemory() {
        val prefs = preferences()
        val repository = MainAgentSpeedDefaultsRepository(prefs)
        val p = provider()
        repository.remember(p, a, GptSpeedMode.FAST)
        val before = prefs.all.toMap()
        val nonGpt = a.copy(modelId = "deepseek-chat")
        assertFalse(repository.remember(p.copy(models = listOf(nonGpt)), nonGpt, GptSpeedMode.NORMAL))
        assertFalse(repository.remember(null, a, GptSpeedMode.NORMAL))
        assertFalse(repository.remember(p, null, GptSpeedMode.NORMAL))
        assertFalse(repository.remember(p.copy(isEnabled = false), a, GptSpeedMode.NORMAL))
        assertFalse(repository.remember(p, a.copy(isEnabled = false), GptSpeedMode.NORMAL))
        assertFalse(repository.remember(p.copy(models = emptyList()), a, GptSpeedMode.NORMAL))
        assertFalse(repository.remember(p.copy(models = listOf(a, a)), a, GptSpeedMode.NORMAL))
        assertFalse(repository.remember(p.copy(id = ""), a, GptSpeedMode.NORMAL))
        assertEquals(before, prefs.all)
        assertEquals(GptSpeedMode.FAST, repository.modeFor(p.id, a.id))
    }

    @Test fun legacyUnboundAndChildStateAreNotInheritedOrMutated() {
        val prefs = preferences()
        prefs.edit().putString("gptSpeedMode", "ULTRA_FAST")
            .putString("agent_subagent_model_defaults_v1", "child sentinel")
            .putString("agent_subagent_presets_v1", "preset sentinel").commit()
        val repository = MainAgentSpeedDefaultsRepository(prefs)
        assertEquals(GptSpeedMode.NORMAL, repository.modeFor("p", a.id))
        assertEquals(GptSpeedMode.NORMAL, repository.modeFor("", a.id))
        repository.remember(provider(), a, GptSpeedMode.FAST)
        assertEquals("child sentinel", prefs.getString("agent_subagent_model_defaults_v1", null))
        assertEquals("preset sentinel", prefs.getString("agent_subagent_presets_v1", null))
        assertEquals("ULTRA_FAST", prefs.getString("gptSpeedMode", null))
        assertEquals(GptSpeedMode.NORMAL, repository.modeFor("p", b.id))
    }

    @Test fun fullPreferencesBackupAndRestoreUsesFreshScalarNotStaleRepositoryMemory() {
        val context = RuntimeEnvironment.getApplication() as Context
        Prefs.initLocal(context)
        val prefs = requireNotNull(Prefs.localAgentPreferences())
        prefs.edit().clear().commit()
        try {
            val repository = MainAgentSpeedDefaultsRepository(prefs)
            val p = provider()
            repository.remember(p, a, GptSpeedMode.FAST)
            repository.remember(p, b, GptSpeedMode.ULTRA_FAST)
            val backup = Prefs.exportAgentPreferences()
            assertTrue(backup.getValue(MainAgentSpeedDefaultsRepository.KEY).startsWith("s:"))
            repository.remember(p, a, GptSpeedMode.NORMAL)
            Prefs.restoreAgentPreferences(backup)
            assertEquals(GptSpeedMode.FAST, repository.modeFor(p.id, a.id))
            assertEquals(GptSpeedMode.ULTRA_FAST, MainAgentSpeedDefaultsRepository(prefs).modeFor(p.id, b.id))
            // Old backup without model-bound history must not distribute an unbound choice.
            Prefs.restoreAgentPreferences(mapOf("gptSpeedMode" to "s:ULTRA_FAST"))
            assertEquals(GptSpeedMode.NORMAL, repository.modeFor(p.id, a.id))
            assertEquals(GptSpeedMode.NORMAL, repository.modeFor(p.id, b.id))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun malformedOrFutureStoreIsRetainedInsteadOfResettingOtherSelections() {
        val prefs = preferences()
        for (raw in listOf("broken", "{\"version\":2,\"models\":[]}",
            "{\"version\":1,\"models\":[{\"provider\":\"p\",\"model\":\"m\",\"speed\":\"FUTURE\"}]}")) {
            prefs.edit().putString(MainAgentSpeedDefaultsRepository.KEY, raw).commit()
            val repository = MainAgentSpeedDefaultsRepository(prefs)
            assertEquals(GptSpeedMode.NORMAL, repository.modeFor("p", a.id))
            assertTrue(runCatching { repository.remember(provider(), a, GptSpeedMode.FAST) }.isFailure)
            assertEquals(raw, prefs.getString(MainAgentSpeedDefaultsRepository.KEY, null))
        }
    }

    @Test fun commitFalseAfterMemoryMutationRollsBackExactOldScalarAndAllOtherChoices() {
        val prefs = preferences()
        val p = provider()
        val repository = MainAgentSpeedDefaultsRepository(prefs)
        repository.remember(p, a, GptSpeedMode.FAST)
        repository.remember(p, b, GptSpeedMode.ULTRA_FAST)
        val before = prefs.all.toMap()
        val failing = CommitFailurePreferences(prefs)
        failing.failuresRemaining = 1
        assertTrue(runCatching {
            MainAgentSpeedDefaultsRepository(failing).remember(p, a, GptSpeedMode.NORMAL)
        }.isFailure)
        assertNotEquals(before[MainAgentSpeedDefaultsRepository.KEY], failing.scalarSeenBeforeFalse)
        assertEquals(before, prefs.all)
        assertEquals(GptSpeedMode.FAST, MainAgentSpeedDefaultsRepository(failing).modeFor(p.id, a.id))
        assertEquals(GptSpeedMode.FAST, repository.modeFor(p.id, a.id))
        assertEquals(GptSpeedMode.ULTRA_FAST, repository.modeFor(p.id, b.id))
    }

    @Test fun failingFirstWriteRestoresAbsenceEvenWhenRollbackDiskCommitAlsoFails() {
        val prefs = preferences()
        prefs.edit().putString("unrelated", "keep").commit()
        val before = prefs.all.toMap()
        val failing = CommitFailurePreferences(prefs).also { it.failuresRemaining = 2 }
        val repository = MainAgentSpeedDefaultsRepository(failing)
        assertTrue(runCatching { repository.remember(provider(), a, GptSpeedMode.FAST) }.isFailure)
        assertEquals(before, prefs.all)
        assertFalse(prefs.contains(MainAgentSpeedDefaultsRepository.KEY))
        assertEquals(GptSpeedMode.NORMAL, repository.modeFor("p", a.id))
        assertEquals(GptSpeedMode.NORMAL, MainAgentSpeedDefaultsRepository(prefs).modeFor("p", a.id))
    }

    /** Match Android's contract: a failed disk commit has already updated the memory map. */
    private class CommitFailurePreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        var failuresRemaining = 0
        var scalarSeenBeforeFalse: String? = null
        override fun edit(): SharedPreferences.Editor {
            val editor = delegate.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun remove(key: String): SharedPreferences.Editor {
                    editor.remove(key)
                    return this
                }
                override fun commit(): Boolean {
                    if (failuresRemaining <= 0) return editor.commit()
                    failuresRemaining--
                    editor.apply() // Real SharedPreferences publishes changes synchronously in memory.
                    scalarSeenBeforeFalse = delegate.getString(MainAgentSpeedDefaultsRepository.KEY, null)
                    return false
                }
            }
        }
    }
}
