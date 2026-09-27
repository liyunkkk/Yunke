package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentBoundaryGuidanceTest {
    @Test fun boundaryQueueDoesNotCancelLiveSseOrSuppressNextEvent() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        val sentFirst = CountDownLatch(1)
        val sendSecond = CountDownLatch(1)
        server.createContext("/stream") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("data: first\n\n".toByteArray())
                out.flush()
                sentFirst.countDown()
                if (sendSecond.await(5, TimeUnit.SECONDS)) {
                    out.write("data: second\n\n".toByteArray())
                    out.flush()
                }
            }
        }
        server.start()
        val controller = AgentRunController()
        val seenFirst = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true) {
            try {
                AgentSseClient.collect(Request.Builder().url("http://127.0.0.1:${server.address.port}/stream").build(),
                    controller, onEvent = { _, _, data -> seen += data; if (data == "first") seenFirst.countDown() })
            } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            assertTrue(sentFirst.await(5, TimeUnit.SECONDS))
            assertTrue(seenFirst.await(5, TimeUnit.SECONDS))
            assertTrue(controller.queueBoundaryGuidance("Review the actual results"))
            assertTrue(controller.hasPendingSteering)
            assertFalse(controller.hasPendingImmediateSteering)
            assertFalse("queue must not finish the active SSE", finished.await(150, TimeUnit.MILLISECONDS))
            sendSecond.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(listOf("first", "second"), seen.toList())
            assertEquals("Review the actual results", controller.pollSteeringMessage())
        } finally {
            sendSecond.countDown()
            controller.cancel()
            worker.join(2000)
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test fun naturalFinishSealsOnlyAfterDrainingGuidance() {
        val controller = AgentRunController()
        var rounds = 0
        val provider = object : AgentProviderClient {
            override val id = "boundary-finish"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                rounds++
                if (rounds == 1) assertTrue(controller.queueBoundaryGuidance("Review this before finalizing"))
                if (rounds == 2) assertTrue(request.messages.toString().contains("Review this before finalizing"))
                return ProviderResponse(JSONObject().put("role", "assistant")
                    .put("content", "answer $rounds").put("finish_reason", "stop"))
            }
        }
        val result = AgentLoop(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test-key",
                model = "test-model", systemPrompt = "", browserTools = false),
            JSONArray().put(AgentConversationCodec.userTextMessage("task")), JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(), {},
        ).run()
        assertEquals(2, rounds)
        assertEquals("answer 2", result.content)
        assertFalse(controller.queueBoundaryGuidance("too late"))
    }

    @Test fun midBatchGuidanceArrivesAfterAllToolResults() {
        val controller = AgentRunController()
        var rounds = 0
        val executed = mutableListOf<String>()
        val provider = object : AgentProviderClient {
            override val id = "boundary-batch"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                rounds++
                if (rounds == 2) {
                    val suffix = (request.messages.length() - 4 until request.messages.length())
                        .map { request.messages.getJSONObject(it).optString("role") }
                    assertEquals(listOf("assistant", "tool", "tool", "user"), suffix)
                    assertTrue(request.messages.getJSONObject(request.messages.length() - 1).toString().contains("Review both"))
                }
                return if (rounds == 1) {
                    val calls = JSONArray()
                    listOf("a", "b").forEach { id ->
                        calls.put(JSONObject().put("id", id).put("type", "function")
                            .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}")))
                    }
                    ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("tool_calls", calls).put("finish_reason", "tool_calls"))
                } else ProviderResponse(JSONObject().put("role", "assistant")
                    .put("content", "complete").put("finish_reason", "stop"))
            }
        }
        val result = AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test-key",
                model = "test-model", systemPrompt = "", browserTools = false),
            prompt = "task", provider = provider, runController = controller,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                if (call.id == "a") assertTrue(controller.queueBoundaryGuidance("Review both"))
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
        )
        assertEquals(listOf("a", "b"), executed)
        assertEquals("complete", result.content)
        assertEquals(2, rounds)
    }
}
