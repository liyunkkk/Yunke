package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderUrls
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudSpeechSynthesizerTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com/v1/", apiKey = "secret-key", model = "tts-1", systemPrompt = "never send")
    private val mp3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0, 0)
    private fun client(code: Int = 200, type: String = "audio/mpeg", bytes: ByteArray = mp3, inspect: (Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .header("Content-Type", type).body(bytes.toResponseBody(type.toMediaType())).build()
        }.build()

    @Test fun speechPayloadIsIndependentOfAgentProtocol() = runBlocking {
        val http = client { request ->
            assertEquals("https://example.com/v1/audio/speech", request.url.toString())
            assertEquals("Bearer secret-key", request.header("Authorization"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val json = JSONObject(body)
            assertEquals("tts-1", json.getString("model"))
            assertEquals("你好", json.getString("input"))
            assertEquals("provider-voice", json.getString("voice"))
            assertEquals("mp3", json.getString("response_format"))
            assertFalse(json.has("tools"))
            assertFalse(json.has("messages"))
            assertFalse(body.contains("never send"))
        }
        assertArrayEquals(mp3, CloudSpeechSynthesizer(http).synthesize(config, "你好", "provider-voice"))
    }
    @Test fun relayAliasesSendCanonicalPresetVoicesToConfiguredHost() = runBlocking {
        for ((model, engine, prefix) in listOf(
            Triple("CosyVoice2", SpeechEngine.COSYVOICE, "FunAudioLLM/CosyVoice2-0.5B"),
            Triple("MOSS-TTSD", SpeechEngine.MOSS, "fnlp/MOSS-TTSD-v0.5"),
        )) {
            val http = client { request ->
                assertEquals("https://example.com/v1/audio/speech", request.url.toString())
                val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                assertEquals(model, body.getString("model"))
                assertEquals("$prefix:alex", body.getString("voice"))
            }
            val voice = SpeechVoices.catalog(engine, model).first().id
            assertArrayEquals(mp3, CloudSpeechSynthesizer(http).synthesize(config.copy(model = model), "你好", voice))
        }
    }
    @Test fun noUniversalVoiceIsSilentlyInvented() = runBlocking {
        val message = failure { CloudSpeechSynthesizer(client()).synthesize(config, "你好", "") }
        assertTrue(message.contains("音色"))
    }
    @Test fun htmlAndJsonSuccessAreRejected() = runBlocking {
        for (type in listOf("text/html", "application/json", "audio/mpeg")) {
            val message = failure { CloudSpeechSynthesizer(client(type = type, bytes = "{\"error\":\"secret-key\"}".toByteArray())).synthesize(config, "你好", "a") }
            assertFalse(message.contains("secret-key"))
            assertTrue(message.contains("音频"))
        }
    }
    @Test fun upstreamErrorBodyNeverLeaks() = runBlocking {
        val message = failure { CloudSpeechSynthesizer(client(401, "text/html", "secret-key/private prose".toByteArray())).synthesize(config, "你好", "a") }
        assertTrue(message.contains("401"))
        assertFalse(message.contains("secret-key"))
        assertFalse(message.contains("private prose"))
    }
    @Test fun oversizedAudioIsRejected() = runBlocking {
        val message = failure { CloudSpeechSynthesizer(client(bytes = ByteArray(CloudSpeechSynthesizer.MAX_AUDIO_BYTES + 1))).synthesize(config, "你好", "a") }
        assertTrue(message.contains("大小"))
    }
    @Test fun cancellationCancelsTheActualCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor { chain ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .header("Content-Type", "audio/mpeg").body(mp3.toResponseBody()).build()
        }.build()
        val pending = async(Dispatchers.Default) { CloudSpeechSynthesizer(http).synthesize(config, "你好", "a") }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            pending.cancel()
            release.countDown()
        }
    }
    @Test fun validMp3WithGenericMimeIsAcceptedButEmptyIsNot() {
        CloudSpeechSynthesizer.validateAudio("application/octet-stream", mp3)
        CloudSpeechSynthesizer.validateAudio("audio/mpeg", byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0))
        assertThrows(SpeechPlaybackFailure::class.java) { CloudSpeechSynthesizer.validateAudio("audio/mpeg", byteArrayOf()) }
    }
    @Test fun speechUrlKeepsConfiguredApiPrefix() {
        assertEquals("https://example.com/api/v1/audio/speech", ProviderUrls.openAiAudioSpeechUrl("https://example.com/api/v1/"))
    }
    private suspend fun failure(block: suspend () -> Unit): String {
        try { block() } catch (e: SpeechPlaybackFailure) { return e.message.orEmpty() }
        throw AssertionError("Expected a controlled speech failure")
    }
}
