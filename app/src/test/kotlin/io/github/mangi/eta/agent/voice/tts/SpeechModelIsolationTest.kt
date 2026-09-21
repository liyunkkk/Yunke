package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import org.junit.Assert.*
import org.junit.Test

class SpeechModelIsolationTest {
    private val provider = OpenAiCompatibleProviderSetting(
        id = "p", name = "p", baseUrl = "https://example.com/v1", apiKey = "key",
        models = listOf(Model("chat", "gpt-chat", "chat"), Model("voice", "tts-1", "voice")),
    )
    @Test fun chatPickerExcludesDedicatedTtsEvenForOldSavedSelection() {
        val state = AgentModelPickerProjector.project(listOf(provider), "p", "voice")
        assertNull(state.selectedModel)
        assertEquals(listOf("chat"), state.providerGroups.single().models.map { it.id })
    }
    @Test fun speechPickerCanExplicitlySelectVoiceModel() {
        val state = AgentModelPickerProjector.project(listOf(provider), "p", "voice", includeSpeechModels = true)
        assertEquals("voice", state.selectedModel?.id)
    }
    @Test fun directAgentClientCannotBypassThePicker() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com/v1", apiKey = "", model = "tts-1", systemPrompt = "")
        assertThrows(IllegalArgumentException::class.java) { ProviderClientFactory.getClient(config) }
    }

    @Test fun arkProviderDoesNotReceiveDoubaoSpeechCatalog() {
        val volc = OpenAiCompatibleProviderSetting(
            id = "v", name = "火山", baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3", apiKey = "key",
            models = listOf(
                Model("chat", "doubao-seed-2-1-pro-260915", "chat"),
                Model("seed-tts-2.0", "seed-tts-2.0", "tts"),
            ),
        )
        val chat = AgentModelPickerProjector.project(listOf(volc), "v", "chat")
        assertEquals(listOf("doubao-seed-2-1-pro-260915"), chat.providerGroups.single().models.map { it.modelId })
        val extras = SpeechSynthesisModels.catalogModels(volc).map { it.modelId }
        assertTrue(extras.isEmpty())
        val stripped = SpeechSynthesisModels.mergeCatalog(volc).map { it.modelId }
        assertEquals(listOf("doubao-seed-2-1-pro-260915"), stripped)
    }

    @Test fun openspeechProviderHiddenFromChatPicker() {
        val speech = OpenAiCompatibleProviderSetting(
            id = "s", name = "豆包语音", baseUrl = "https://openspeech.bytedance.com", apiKey = "key",
            models = listOf(Model("seed-tts-2.0", "seed-tts-2.0", "tts")),
        )
        val chat = AgentModelPickerProjector.project(listOf(speech), "s", "seed-tts-2.0")
        assertTrue(chat.providerGroups.isEmpty())
        val picker = AgentModelPickerProjector.project(listOf(speech), "s", "seed-tts-2.0", includeSpeechModels = true)
        assertEquals(listOf("seed-audio-1.0", "seed-tts-2.0"), picker.providerGroups.single().models.map { it.modelId })
    }

    @Test fun chatProviderCosyVoiceAppearsOnlyInReadAloudPicker() {
        val chat = OpenAiCompatibleProviderSetting(
            id = "fish", name = "鱼", baseUrl = "https://api.example.com/v1", apiKey = "key",
            models = listOf(Model("chat", "gpt-chat", "chat"), Model("voice", "CosyVoice2", "CosyVoice2")),
        )
        val tts = AgentModelPickerProjector.project(listOf(chat), "fish", "voice", includeSpeechModels = true, speechOnly = true)
        assertEquals(listOf("CosyVoice2"), tts.providerGroups.single().models.map { it.modelId })
        val chatPicker = AgentModelPickerProjector.project(listOf(chat), "fish", "chat")
        assertEquals(listOf("gpt-chat"), chatPicker.providerGroups.single().models.map { it.modelId })
    }

    @Test fun dedicatedSpeechProviderListsCosyVoiceAndMoss() {
        val speech = OpenAiCompatibleProviderSetting(
            id = "s", name = "语音合成", baseUrl = "https://api.example.com/v1", apiKey = "key",
            sourceType = io.github.mangi.eta.data.model.ProviderSourceTypes.COMPATIBLE_SPEECH,
        )
        val chat = AgentModelPickerProjector.project(listOf(speech), "s", "CosyVoice2")
        assertTrue(chat.providerGroups.isEmpty())
        val picker = AgentModelPickerProjector.project(listOf(speech), "s", "CosyVoice2", includeSpeechModels = true, speechOnly = true)
        assertEquals(listOf("CosyVoice2", "MOSS-TTSD"), picker.providerGroups.single().models.map { it.modelId })
    }
}
