package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentModelClient

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.model.ProviderSourceTypes
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineTest {
    @Test fun mimoHostUsesMimoEngineAndVoices() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "m",
            name = "mimo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            apiKey = "k",
            sourceType = ProviderSourceTypes.MIMO,
        )
        assertEquals(SpeechEngine.MIMO, SpeechEngineResolver.resolve(provider, "mimo-v2.5-tts-voiceclone"))
        assertEquals("mimo_default", SpeechVoices.catalog(SpeechEngine.MIMO).first().id)
        assertTrue(SpeechVoices.catalog(SpeechEngine.MIMO).none { it.id == "alloy" })
    }

    @Test fun mimoSseConcatenatesPcm() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(pcm)
        val sse = """
            data: {"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}

            data: [DONE]

        """.trimIndent()
        assertTrue(SpeechProtocols.decodeMimoSse(sse).contentEquals(pcm))
    }

    @Test fun chatRelaysResolveTheirActualSpeechModel() {
        val chat = OpenAiCompatibleProviderSetting(
            id = "fish", name = "鱼", baseUrl = "https://api.example.com/v1", apiKey = "k",
        )
        assertEquals(SpeechEngine.COSYVOICE, SpeechEngineResolver.resolve(chat, "CosyVoice2"))
        assertTrue(SpeechSynthesisModels.isReadAloudModel(Model("c", "CosyVoice2", "CosyVoice2"), chat))
    }

    @Test fun dedicatedSpeechProviderMapsCosyVoiceAndMoss() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "speech", name = "语音合成", baseUrl = "https://api.example.com/v1", apiKey = "k",
            sourceType = ProviderSourceTypes.COMPATIBLE_SPEECH,
        )
        assertEquals(SpeechEngine.COSYVOICE, SpeechEngineResolver.resolve(provider, "CosyVoice2"))
        assertEquals(SpeechEngine.MOSS, SpeechEngineResolver.resolve(provider, "MOSS-TTSD"))
        val cosy = SpeechVoices.catalog(SpeechEngine.COSYVOICE, "CosyVoice2")
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:alex", cosy.first().id)
        assertTrue(cosy.any { it.id == "FunAudioLLM/CosyVoice2-0.5B:anna" })
        assertTrue(cosy.none { it.id == "alloy" })
        assertEquals("fnlp/MOSS-TTSD-v0.5:alex", SpeechVoices.catalog(SpeechEngine.MOSS, "MOSS-TTSD").first().id)
        val request = SpeechProtocols.request(
            SpeechEngine.COSYVOICE,
            AgentModelClient.ModelConfig(baseUrl = "https://api.example.com/v1", apiKey = "k", model = "CosyVoice2", systemPrompt = ""),
            "你好",
            "FunAudioLLM/CosyVoice2-0.5B:alex",
        )
        assertTrue(request.url.toString().endsWith("/audio/speech"))
    }
}
