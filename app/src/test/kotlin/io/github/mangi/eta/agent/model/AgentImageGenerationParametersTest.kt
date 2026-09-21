package io.github.mangi.eta.agent.model

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentImageGenerationParametersTest {
    private fun config(model: String = "grok-imagine-image-2.0") = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = model, systemPrompt = "")
    private fun png(w: Int = 90, h: Int = 160): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray()
        }
    }
    private fun response(bytes: ByteArray) = JSONObject().put("data", JSONArray().put(JSONObject()
        .put("b64_json", Base64.getEncoder().encodeToString(bytes)))).toString()
    private fun client(requests: MutableList<Request>, result: String, code: Int = 200) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(result.toResponseBody()).build()
        }.build()
    private fun body(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    @Test fun realGenerationBodyCarriesGrokRatioResolutionAndPerCallOverrides() {
        val requests = mutableListOf<Request>()
        val configured = config().copy(extraBodyJson = """{"size":"1024x1024","aspect_ratio":"1:1","resolution":"1k"}""")
        val result = AgentImageGenerationClient(client(requests, response(png()))).generate(configured, "portrait",
            options = AgentImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        val json = JSONObject(body(requests.single()))
        assertTrue(requests.single().url.encodedPath.endsWith("/images/generations"))
        assertEquals("9:16", json.getString("aspect_ratio")); assertEquals("2k", json.getString("resolution"))
        assertFalse(json.has("size")); assertEquals("portrait", json.getString("prompt"))
        assertTrue(configured.extraBodyJson.contains("1:1"))
        assertEquals(90, result.images.single().width); assertEquals(160, result.images.single().height)
        assertTrue(result.text.contains("IMAGE_DIMENSIONS_MISMATCH")) // tiny output cannot satisfy 2k
    }
    @Test fun squareResultIsReportedNotRetriedOrResized() {
        val requests = mutableListOf<Request>()
        val output = AgentImageGenerationClient(client(requests, response(png(100, 100)))).generate(config(), "portrait",
            options = AgentImageGenerationOptions(aspectRatio = "9:16"))
        assertEquals(1, requests.size)
        assertEquals(100, output.images.single().width)
        assertTrue(output.text.contains("IMAGE_DIMENSIONS_MISMATCH"))
    }
    @Test fun unsupportedParametersNeverCallApiAndEndpointFailureNeverFallsBackToChat() {
        val requests = mutableListOf<Request>()
        val generator = AgentImageGenerationClient(client(requests, """{"error":{"message":"unsupported endpoint"}}""", 404))
        assertThrows(ImageGenerationParameterException::class.java) {
            generator.generate(config(), "portrait", options = AgentImageGenerationOptions(size = "1080x1920"))
        }
        assertTrue(requests.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            generator.generate(config(), "portrait", options = AgentImageGenerationOptions(aspectRatio = "9:16"))
        }
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/images/generations"))
    }
    @Test fun grokEditIsJsonAndCarriesOptionsAndReferenceImage() {
        val requests = mutableListOf<Request>()
        AgentImageGenerationClient(client(requests, response(png()))).generate(config(), "edit portrait",
            images = listOf(AgentImageGenerationClient.InputImage(png(), "image/png")),
            options = AgentImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        assertTrue(requests.single().url.encodedPath.endsWith("/images/edits"))
        assertTrue(requests.single().body!!.contentType().toString().startsWith("application/json"))
        val json = JSONObject(body(requests.single()))
        assertEquals("9:16", json.getString("aspect_ratio")); assertEquals("2k", json.getString("resolution"))
        assertEquals("image_url", json.getJSONObject("image").getString("type"))
        assertTrue(json.getJSONObject("image").getString("url").startsWith("data:image/png;base64,"))
    }
    @Test fun openAiMultipartEditIncludesRequestedSizeAndDoesNotDropReferences() {
        val requests = mutableListOf<Request>()
        AgentImageGenerationClient(client(requests, response(png()))).generate(config("gpt-image-1"), "edit",
            images = listOf(AgentImageGenerationClient.InputImage(png(), "image/png")),
            options = AgentImageGenerationOptions(size = "1024x1536"))
        val encoded = body(requests.single())
        assertTrue(requests.single().body!!.contentType().toString().startsWith("multipart/form-data"))
        assertTrue(encoded.contains("name=\"size\"")); assertTrue(encoded.contains("1024x1536"))
        assertTrue(encoded.contains("name=\"image\""))
    }
    @Test fun gpt2RatioBecomesExactSizeAndConfiguredGrokDefaultsSurviveWithoutExplicitOptions() {
        val requests = mutableListOf<Request>()
        val gen = AgentImageGenerationClient(client(requests, response(png())))
        gen.generate(config("gpt-image-2"), "portrait", options = AgentImageGenerationOptions(aspectRatio = "9:16"))
        val body1 = JSONObject(body(requests.single()))
        assertEquals("864x1536", body1.getString("size")); assertFalse(body1.has("aspect_ratio"))
        requests.clear()
        gen.generate(config().copy(extraBodyJson = """{"aspect_ratio":"9:16","resolution":"2k"}"""), "portrait")
        assertEquals("9:16", JSONObject(body(requests.single())).getString("aspect_ratio"))
    }
    @Test fun ambiguousFailureOrSuccessfulEmptyResponseDoesNotGenerateAgain() {
        listOf(200, 400, 502, 503).forEach { code ->
            val requests = mutableListOf<Request>()
            assertThrows(IllegalStateException::class.java) {
                AgentImageGenerationClient(client(requests, "{}", code)).generate(config(), "image")
            }
            assertEquals(1, requests.size)
        }
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request(); throw java.io.IOException("response lost")
        }.build()
        assertThrows(java.io.IOException::class.java) {
            AgentImageGenerationClient(http).generate(config(), "image")
        }
        assertEquals(1, requests.size)
    }

}
