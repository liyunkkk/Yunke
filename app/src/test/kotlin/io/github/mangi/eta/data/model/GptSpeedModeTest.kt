package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GptSpeedModeTest {
    @Test fun cyclesThroughAllThreeModes() {
        assertEquals(GptSpeedMode.FAST, GptSpeedMode.NORMAL.next())
        assertEquals(GptSpeedMode.ULTRA_FAST, GptSpeedMode.FAST.next())
        assertEquals(GptSpeedMode.NORMAL, GptSpeedMode.ULTRA_FAST.next())
    }

    @Test fun acceptsNumberedGptIdsWithoutAVersionAllowlist() {
        listOf("gpt-4o", "gpt-4.1-mini", "gpt-5.4", "gpt-99-relay", "gpt-5-codex",
            " OpenAI/GPT-5.4 ", "provider/team/gpt-5-mini:free").forEach {
            assertTrue(it, isGptSpeedModel(it))
        }
    }

    @Test fun rejectsOtherBrandsSubstringsAndNonTextModels() {
        listOf("", "gpt", "gpt-", "gpt-oss-120b", "o3", "openai/o3", "codex", "codex-mini",
            "foo-gpt-5", "provider/foo-gpt-5", "my gpt-5", "provider/gpt-5/chat",
            "gpt-image-1", "gpt-audio", "gpt-realtime", "gpt-4o-audio-preview",
            "openai/GPT-4O-REALTIME-PREVIEW", "gpt-4o-mini-tts", "gpt-4o-transcribe",
            "gpt-4o-mini-transcribe", "gpt-99-image-preview").forEach {
            assertFalse(it, isGptSpeedModel(it))
        }
    }

    @Test fun bindingRejectsUnknownProtocolsAndMediaEvenWithGptIds() {
        val model = Model(id = "m", modelId = "gpt-6-astra", displayName = "Other display name")
        val provider = OpenAiCompatibleProviderSetting(id = "p", name = "relay", baseUrl = "https://example.invalid")
        assertTrue(supportsGptSpeedBinding(provider, model))
        assertTrue(supportsGptSpeedBinding(provider.copy(endpointMode = OpenAiEndpointMode.RESPONSES), model))
        assertFalse(supportsGptSpeedBinding(provider.copy(endpointMode = "unknown"), model))
        assertFalse(supportsGptSpeedBinding(provider.copy(isEnabled = false), model))
        assertFalse(supportsGptSpeedBinding(provider, model.copy(isEnabled = false)))
        assertFalse(supportsGptSpeedBinding(null, model))
        assertFalse(supportsGptSpeedBinding(provider, null))
        assertFalse(supportsGptSpeedBinding(provider, model.copy(modelId = "deepseek-chat", displayName = "gpt-6-astra")))
        for (output in listOf(Model.IMAGE_MODALITY, Model.VIDEO_MODALITY, Model.AUDIO_MODALITY)) {
            assertFalse(output, supportsGptSpeedBinding(provider, model.copy(outputModalities = listOf(output))))
        }
        assertFalse(supportsGptSpeedProtocol(ProviderTypes.CUSTOM, OpenAiEndpointMode.RESPONSES))
        assertFalse(supportsGptSpeedProtocol(ProviderTypes.ANTHROPIC, OpenAiEndpointMode.CHAT_COMPLETIONS))
    }
}
