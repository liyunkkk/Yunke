package io.github.mangi.eta.agent.voice.mimo

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.voice.tts.SpeechProtocols
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import java.util.Base64
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MimoCloneTest {
    @Test fun cloneUsesReferenceDataUriAndNonStreamingWav() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://api.xiaomimimo.com/v1", apiKey = "test", model = "mimo-v2.5-tts", systemPrompt = "")
        val reference = "data:audio/wav;base64,UklGRg=="
        val request = SpeechProtocols.mimoClone(config, "试听正文", reference)
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        val json = JSONObject(buffer.readUtf8())
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals(MimoPersonalVoices.MODEL, json.getString("model"))
        assertFalse(json.getBoolean("stream"))
        assertEquals("wav", json.getJSONObject("audio").getString("format"))
        assertEquals(reference, json.getJSONObject("audio").getString("voice"))
        assertEquals("assistant", json.getJSONArray("messages").getJSONObject(1).getString("role"))
        assertEquals("试听正文", json.getJSONArray("messages").getJSONObject(1).getString("content"))
    }
    @Test fun cloneResponseDecodesWavWithoutAddingSecondHeader() {
        val wav = ByteArray(48)
        "RIFF".toByteArray().copyInto(wav); "WAVE".toByteArray().copyInto(wav, 8)
        val encoded = Base64.getEncoder().encodeToString(wav)
        val json = """{"choices":[{"message":{"audio":{"data":"$encoded"}}}]}"""
        assertArrayEquals(wav, SpeechProtocols.decodeMimoClone(json.toByteArray()))
        assertThrows(Exception::class.java) { SpeechProtocols.decodeMimoClone("{}".toByteArray()) }
    }
    @Test fun sampleValidationChecksEncodedSizeAndFileSignature() {
        assertEquals("audio/mpeg", MimoPersonalVoices.mime("ID3sample".toByteArray()))
        val wav = ByteArray(44); "RIFF".toByteArray().copyInto(wav); "WAVE".toByteArray().copyInto(wav, 8)
        assertEquals("audio/wav", MimoPersonalVoices.mime(wav))
        assertThrows(Exception::class.java) { MimoPersonalVoices.mime("not a wav file".toByteArray()) }
        assertThrows(Exception::class.java) { MimoPersonalVoices.mime(ByteArray(MimoPersonalVoices.MAX_BYTES + 1)) }
    }
    @Test fun ordinaryProviderIsNotOfferedAsMimoAccount() {
        val provider = OpenAiCompatibleProviderSetting("a", "a", "https://api.openai.com/v1", apiKey = "test")
        assertFalse(MimoPersonalVoices.supports(provider))
        assertTrue(MimoPersonalVoices.supports(provider.copy(baseUrl = "https://api.xiaomimimo.com/v1")))
        assertFalse(MimoPersonalVoices.supports(provider.copy(baseUrl = "https://api.xiaomimimo.com/v1", apiKey = "")))
    }
}
