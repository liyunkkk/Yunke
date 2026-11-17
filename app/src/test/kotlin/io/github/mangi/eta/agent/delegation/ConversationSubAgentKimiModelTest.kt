package io.github.mangi.eta.agent.delegation

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Legacy field is ignored on import and omitted from newly exported generic worker settings. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConversationSubAgentKimiModelTest {
    @Test fun retiredLegacyFieldDoesNotAlterGenericWorkers() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(
            "retired-worker-field-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        val repo = ConversationSubAgentPreferences(prefs)
        val source = SubAgentConfigKey.Conversation("source")
        val restored = SubAgentConfigKey.Conversation("restored")
        val original = repo.snapshot(source)
        val payload = JSONObject(repo.export(source)).put("kimi_model", "retired-model").toString()
        assertTrue(repo.importOwner(restored, payload))
        assertEquals(original.profiles, repo.snapshot(restored).profiles)
        assertEquals(original.enabled, repo.snapshot(restored).enabled)
        assertFalse(JSONObject(repo.export(restored)).has("kimi_model"))
    }
}
