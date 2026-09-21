package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AgentGenerationRequestTest {
    private val request = Request.Builder().url("https://example.invalid/generate").build()

    @Test fun alreadyCancelledChildNeverStartsGenerationRequest() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; error("must not execute") }.build()
        val controller = AgentRunController().also { it.cancel() }
        assertThrows(AgentRunCancelledException::class.java) {
            executeGenerationRequest(client, request, controller) { "unused" }
        }
        assertEquals(0, calls)
    }

    @Test fun cancellationBeforeResultPublicationCannotReturnGeneratedOutput() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("payload".toResponseBody()).build()
        }.build()
        val controller = AgentRunController()
        assertThrows(AgentRunCancelledException::class.java) {
            executeGenerationRequest(client, request, controller) {
                controller.cancel()
                "must not return this artifact"
            }
        }
    }

    @Test fun successfulRequestReleasesItsCancellationBinding() {
        lateinit var call: Call
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call = chain.call()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("payload".toResponseBody()).build()
        }.build()
        val controller = AgentRunController()
        assertEquals("payload", executeGenerationRequest(client, request, controller) { it.body.string() })
        controller.cancel()
        assertFalse(call.isCanceled())
    }
}
