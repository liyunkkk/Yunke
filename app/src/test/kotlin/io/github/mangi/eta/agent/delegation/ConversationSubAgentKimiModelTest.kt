package io.github.mangi.eta.agent.delegation

import android.app.Application
import android.content.Context
import io.github.mangi.eta.ui.components.ConversationSubAgentEditor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ConversationSubAgentKimiModelTest {

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("kimi-model-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)

    private fun c(id: String) = SubAgentConfigKey.Conversation(id)

    @Test
    fun kimiModelIsSavedPerConversationAndDefaultsToNull() {
        val repo = ConversationSubAgentPreferences(prefs())
        assertNull(repo.snapshot(c("a")).kimiModel)
        assertTrue(repo.update(c("a")) { it.copy(kimiModel = "kimi-k2") } is ConversationSubAgentPreferences.WriteResult.Saved)
        assertEquals("kimi-k2", repo.snapshot(c("a")).kimiModel)
        // 另一个会话不受影响：模型是「按会话」保存的。
        assertNull(repo.snapshot(c("b")).kimiModel)
    }

    @Test
    fun editorNormalizesBlankModelToFollowDefault() {
        val repo = ConversationSubAgentPreferences(prefs())
        val editor = ConversationSubAgentEditor(c("a"), repo) { true }
        editor.saveKimiModel("  ")
        assertNull(repo.snapshot(c("a")).kimiModel)
        editor.saveKimiModel(" kimi-k2 ")
        assertEquals("kimi-k2", repo.snapshot(c("a")).kimiModel)
        editor.saveKimiModel(null)
        assertNull(repo.snapshot(c("a")).kimiModel)
    }

    @Test
    fun legacyPayloadWithoutKimiModelDecodesToFollowDefault() {
        val repo = ConversationSubAgentPreferences(prefs())
        repo.update(c("legacy")) { it.copy(kimiModel = "kimi-k2") }
        val legacy = JSONObject(repo.export(c("legacy"))).also { it.remove("kimi_model") }.toString()

        assertTrue(repo.importOwner(c("restored"), legacy))

        assertNull(repo.snapshot(c("restored")).kimiModel)
    }
}
