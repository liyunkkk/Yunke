package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaReasoningRequestsTest {
    private fun config(transport: String) = AgentModelClient.ModelConfig(baseUrl=if (transport=="ark_contents") "https://ark.example.invalid" else "https://example.invalid/v1",
        apiKey="test",model="explicit-test",systemPrompt="",extraBodyJson="""{"eta_media_reasoning":{"video_generation":{"transport":"$transport","field":"reasoning_effort","values":{"low":"low","high":"high"},"default":"low"}}}""")
    private fun body(request: Request)=Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    @Test fun mappedEffortReachesEachPinnedVideoTransportExactlyOnce() = runBlocking {
        for(transport in listOf("videos_json","videos_multipart","videos_generations","video_generations","ark_contents")) {
            val requests=mutableListOf<Request>()
            val http=OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("test").body("rejected".toResponseBody()).build()
            }.build()
            val mapped=MediaReasoningSettings.apply(config(transport),"video_generation",ReasoningEffort.HIGH)
            var failed=false
            try { AgentVideoGenerationClient(http).generate(mapped,"cat",transport=transport) }
            catch (_: IllegalStateException) { failed=true }
            assertTrue(failed);assertEquals(transport,1,requests.size)
            val encoded=body(requests.single())
            assertFalse(encoded.contains("eta_media_reasoning"))
            if(transport=="videos_multipart") {
                assertTrue(encoded.contains("name=\"reasoning_effort\""));assertTrue(encoded.contains("high"))
            } else assertEquals("high",JSONObject(encoded).getString("reasoning_effort"))
        }
    }
    @Test fun transportExceptionDoesNotTryAnotherEndpoint() = runBlocking {
        var calls=0
        val http=OkHttpClient.Builder().addInterceptor { calls++;throw java.io.IOException("network uncertain") }.build()
        val mapped=MediaReasoningSettings.apply(config("videos_json"),"video_generation",ReasoningEffort.HIGH)
        try { AgentVideoGenerationClient(http).generate(mapped,"cat",transport="videos_json");fail("expected error") }
        catch (_: IllegalStateException) { }
        assertEquals(1,calls)
    }
    @Test fun imageMappingReachesRealImagesRequestWithoutPrivateMetadata() {
        val requests=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("test").body("rejected".toResponseBody()).build()
        }.build()
        val cfg=config("videos_json").copy(extraBodyJson="""{"eta_media_reasoning":{"image_generation":{"field":"thinking.level","values":{"off":false,"high":true},"default":"off"}}}""")
        for(effort in listOf(ReasoningEffort.HIGH,ReasoningEffort.OFF)) {
            val mapped=MediaReasoningSettings.apply(cfg,"image_generation",effort)
            assertThrows(IllegalStateException::class.java) { AgentImageGenerationClient(http).generate(mapped,"cat") }
        }
        assertEquals(2,requests.size)
        assertTrue(JSONObject(body(requests[0])).getJSONObject("thinking").getBoolean("level"))
        assertFalse(JSONObject(body(requests[1])).getJSONObject("thinking").getBoolean("level"))
        assertTrue(requests.all { !body(it).contains("eta_media_reasoning") && it.url.encodedPath.endsWith("/images/generations") })
    }

}
