package io.github.mangi.eta.data.repository

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ReasoningEffort
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexModelCatalogHttpTest {
    @Test
    fun countTokensUsesSelectedModelAndDoesNotGuessProjectOn404() = runBlocking {
        val paths = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.toString()
            assertEquals("fixture", exchange.requestHeaders.getFirst("x-goog-api-key"))
            assertNull(exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("POST", exchange.requestMethod)
            val first = paths.size == 1
            val payload = if (first) """{"error":{"message":"projects/123/locations/us-central1/model missing"}}"""
                else """{"totalTokens":2}"""
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(if (first) 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val provider = provider("http://127.0.0.1:${server.address.port}/v1beta1").copy(models = listOf(
                Model(id = "neon", modelId = "gemini-4-neon", displayName = "Neon"),
                Model(id = "argon", modelId = "gemini-4-argon", displayName = "Argon"),
            ))
            val failed = RemoteModelFetcher.testVertexConnection(provider, OkHttpClient(), "argon")
            assertEquals("HTTP_404", (failed.exceptionOrNull() as AgentModelFailure).code)
            assertEquals(1, paths.size)
            assertEquals("gemini-4-argon", RemoteModelFetcher.testVertexConnection(provider, OkHttpClient(), "argon").getOrThrow())
            assertEquals(List(2) { "/v1beta1/publishers/google/models/gemini-4-argon:countTokens" }, paths)
            assertTrue(RemoteModelFetcher.fetch(provider).isFailure)
            assertEquals(2, paths.size)
        } finally { server.stop(0) }
    }

    @Test
    fun probeRejectsRedirectsWithoutSendingCredentialsToTarget() {
        var targetCalls = 0
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        target.createContext("/") { exchange ->
            targetCalls++
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val source = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        source.createContext("/") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}/other")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        source.start(); target.start()
        try {
            val failure = assertThrows(AgentModelFailure::class.java) {
                GeminiModelCatalog.testVertexConnection(
                    provider("http://127.0.0.1:${source.address.port}/v1beta1"),
                    OkHttpClient(), AgentRunController(), null,
                )
            }
            assertEquals("HTTP_307", failure.code)
            assertEquals(0, targetCalls)
        } finally { source.stop(0); target.stop(0) }
    }

    @Test
    fun nativeCatalogParsesXHighAndPreservesExplicitNoThinking() {
        val models = GeminiModelCatalog.parsePage("""{"models":[
            {"name":"models/gemini-4-neon","inputTokenLimit":1048576},
            {"name":"models/gemini-4-carbon","thinking":false},
            {"name":"models/gemini-future","thinking":true},
            {"name":"models/gemini-embedding","supportedGenerationMethods":["embedContent"]}]}""").models
        assertEquals(3, models.size)
        assertTrue(ReasoningEffort.XHIGH in models[0].reasoningCapabilities!!.supportedEfforts)
        assertEquals(1_048_576, models[0].effectiveContextWindow)
        assertEquals(false, models[1].reasoning)
        assertNull(models[1].reasoningCapabilities)
        assertNull(models[2].reasoningCapabilities)
    }

    @Test
    fun paginationDeduplicatesAndRejectsRepeatedTokensAndBudgetOverflow() {
        val studio = provider("https://example.test/v1beta").copy(endpointMode = GeminiEndpointMode.AI_STUDIO)
        var page = 0
        val fetched = GeminiModelCatalog.fetch(studio, AgentRunController(), 10_000) { request, _ ->
            assertEquals("fixture", request.header("x-goog-api-key"))
            assertFalse(request.url.query.orEmpty().contains("key="))
            if (page++ == 0) """{"models":[{"name":"models/gemini-4-neon"}],"nextPageToken":"next"}"""
            else """{"models":[{"name":"models/gemini-4-neon"},{"name":"models/gemini-future"}]}"""
        }
        assertEquals(listOf("gemini-4-neon", "gemini-future"), fetched.map { it.modelId })
        assertThrows(IllegalArgumentException::class.java) {
            GeminiModelCatalog.fetch(studio, AgentRunController(), 10_000) { _, _ ->
                """{"models":[],"nextPageToken":"same"}"""
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GeminiModelCatalog.fetch(studio, AgentRunController(), 1) { _, _ -> """{"models":[]}""" }
        }
    }

    private fun provider(baseUrl: String) = GeminiProviderSetting(
        id = "vertex", name = "Vertex", baseUrl = baseUrl, apiKey = "fixture",
        endpointMode = GeminiEndpointMode.VERTEX_AI,
    )
}
