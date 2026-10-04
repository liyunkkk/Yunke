package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Anonymous local responses only; deliberately no provider/domain exceptions or replay. */
class AgentResponseCompatibilityTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test fun chatJsonWorksWithJsonSseWrongAndMissingMime() {
        val message = JSONObject().put("content", "answer").put("reasoning_content", "thought")
            .put("tool_calls", JSONArray().put(chatCall()))
        val body = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", message).put("finish_reason", "tool_calls"))).put("usage", chatUsage()).toString()
        for (mime in mimeTypes) withBody("\uFEFF \r\n$body", mime) { url, requests ->
            val events = mutableListOf<ProviderEvent>()
            val assistant = complete(OpenAiChatCompletionsProvider, url, events, stream = false)
            assertAssistant(assistant, "answer", "thought", "tool_calls")
            assertEquals(3, assistant.getJSONObject("usage").getInt("output_tokens"))
            assertBlocks(events, "answer", "thought", "{}")
            assertEquals(1, requests.get())
        }
    }

    @Test fun chatSseWorksWithJsonSseWrongAndMissingMimeRegardlessOfRequestedStream() {
        val body = ": heartbeat\r\n\r\n" + frame(JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("reasoning_content", "thought"))))) +
            frame(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "answer"))))) +
            frame(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(chatCall())))))) +
            frame(JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()).put("finish_reason", "tool_calls")))
                .put("usage", chatUsage())) + "data: [DONE]\n\n"
        for (mime in mimeTypes) for (stream in listOf(true, false)) withBody(body, mime) { url, requests ->
            val events = mutableListOf<ProviderEvent>()
            val assistant = complete(OpenAiChatCompletionsProvider, url, events, stream)
            assertAssistant(assistant, "answer", "thought", "tool_calls")
            assertBlocks(events, "answer", "thought", "{}")
            assertEquals(1, requests.get())
        }
    }

    @Test fun chatJsonReasoningOnlyAndToolOnlyMessagesDoNotRequireVisibleContent() {
        for (message in listOf(
            JSONObject().put("content", JSONObject.NULL).put("reasoning_content", "thought"),
            JSONObject().put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(chatCall())),
        )) withBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)
            .put("finish_reason", if (message.has("tool_calls")) "tool_calls" else "stop"))).toString(), "text/event-stream") { url, requests ->
            val assistant = complete(OpenAiChatCompletionsProvider, url)
            assertEquals("", assistant.getString("content"))
            assertEquals(message.has("tool_calls"), assistant.has("tool_calls"))
            assertEquals(1, requests.get())
        }
    }

    @Test fun responsesJsonReusesTerminalOutputUsageAndOpaqueItems() {
        val response = responsesSnapshot()
        for (mime in mimeTypes) withBody(response.toString(), mime) { url, requests ->
            val events = mutableListOf<ProviderEvent>()
            val assistant = complete(OpenAiResponsesProvider, url, events)
            assertAssistant(assistant, "answer", "thought", "tool_calls")
            assertBlocks(events, "answer", "thought", "{}")
            assertNotNull(ResponsesEphemeralState.outputItems(assistant))
            assertEquals(3, events.filterIsInstance<ProviderEvent.Usage>().last().usage.outputTokens)
            assertEquals(1, requests.get())
        }
    }

    @Test fun responsesSseStillUsesDeltaAndTerminalPathsWhenMimeIsJson() {
        val body = frame(JSONObject().put("type", "response.output_text.delta").put("delta", "answer")) +
            frame(JSONObject().put("type", "response.completed").put("response", responsesSnapshot()))
        for (mime in mimeTypes) withBody(body, mime) { url, requests ->
            val events = mutableListOf<ProviderEvent>()
            val assistant = complete(OpenAiResponsesProvider, url, events)
            assertAssistant(assistant, "answer", "thought", "tool_calls")
            assertEquals("answer", events.filterIsInstance<ProviderEvent.BlockDelta>()
                .filter { it.kind == AssistantBlockKind.TEXT }.joinToString("") { it.delta })
            assertEquals(1, requests.get())
        }
    }

    @Test fun anthropicJsonAndSsePreserveReasoningToolsUsageAndStopReason() {
        val message = JSONObject().put("type", "message").put("role", "assistant").put("stop_reason", "tool_use")
            .put("usage", JSONObject().put("input_tokens", 4).put("output_tokens", 3))
            .put("content", JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "thought"))
                .put(JSONObject().put("type", "text").put("text", "answer"))
                .put(JSONObject().put("type", "tool_use").put("id", "call_local").put("name", "inspect").put("input", JSONObject())))
        var sse = frame(JSONObject().put("type", "message_start").put("message", message))
        for (index in 0..2) {
            val block = message.getJSONArray("content").getJSONObject(index)
            sse += frame(JSONObject().put("type", "content_block_start").put("index", index).put("content_block", block))
            val delta = when (index) {
                0 -> JSONObject().put("type", "thinking_delta").put("thinking", "thought")
                1 -> JSONObject().put("type", "text_delta").put("text", "answer")
                else -> JSONObject().put("type", "input_json_delta").put("partial_json", "{}")
            }
            sse += frame(JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))
            sse += frame(JSONObject().put("type", "content_block_stop").put("index", index))
        }
        sse += frame(JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "tool_use"))
            .put("usage", message.getJSONObject("usage"))) + frame(JSONObject().put("type", "message_stop"))
        for (body in listOf(message.toString(), sse)) for (mime in mimeTypes) withBody(body, mime) { url, requests ->
            val events = mutableListOf<ProviderEvent>()
            val assistant = complete(AnthropicMessagesProvider, url, events)
            assertAssistant(assistant, "answer", "thought", "tool_use")
            assertBlocks(events, "answer", "thought", "{}")
            assertEquals(listOf("{}"), events.filterIsInstance<ProviderEvent.BlockDelta>()
                .filter { it.kind == AssistantBlockKind.TOOL_CALL }.map { it.delta })
            val usage = events.filterIsInstance<ProviderEvent.Usage>().last().usage
            assertEquals(4, usage.inputTokens)
            assertEquals(3, usage.outputTokens)
            assertEquals(1, requests.get())
        }
    }

    @Test fun anthropicJsonNonemptyToolInputIsNotAppendedTwice() {
        val input = JSONObject().put("path", "src").put("limit", 2)
        val block = JSONObject().put("type", "tool_use").put("id", "call_local")
            .put("name", "inspect").put("input", input)
        val message = JSONObject().put("type", "message").put("stop_reason", "tool_use")
            .put("content", JSONArray().put(block))
        val sse = frame(JSONObject().put("type", "message_start").put("message", message)) +
            frame(JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", block)) +
            frame(JSONObject().put("type", "content_block_stop").put("index", 0)) +
            frame(JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "tool_use"))) +
            frame(JSONObject().put("type", "message_stop"))
        for (body in listOf(message.toString(), sse)) withBody(body, "text/event-stream") { url, requests ->
            val events = mutableListOf<ProviderEvent>()
            val assistant = complete(AnthropicMessagesProvider, url, events)
            val arguments = assistant.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments")
            assertEquals(input.toString(), arguments)
            assertEquals(input.toString(), events.filterIsInstance<ProviderEvent.BlockEnd>()
                .single { it.kind == AssistantBlockKind.TOOL_CALL }.content)
            assertTrue(events.filterIsInstance<ProviderEvent.BlockDelta>().none { it.kind == AssistantBlockKind.TOOL_CALL })
            assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)
            assertEquals(1, requests.get())
        }
    }

    @Test fun jsonRejectsTrailingGarbageBeforeProviderContentCallbacks() {
        val invalidTails = listOf("garbage", "{}", "\u0000", "/* trailing comment */", "// trailing comment\n")
        for (provider in providers) for (tail in invalidTails) {
            withBody(providerJson(provider).toString() + tail, "text/event-stream") { url, requests ->
                val events = mutableListOf<ProviderEvent>()
                val failure = assertThrows(AgentModelFailure::class.java) { complete(provider, url, events) }
                assertEquals("INVALID_JSON_RESPONSE", failure.code)
                assertFalse(failure.retryable)
                assertNull(failure.cause)
                assertEquals("", failure.diagnostic)
                assertFalse(failure.message.orEmpty().contains("answer"))
                assertTrue(events.none { it is ProviderEvent.BlockStart || it is ProviderEvent.BlockDelta ||
                    it is ProviderEvent.BlockEnd || it is ProviderEvent.Usage || it is ProviderEvent.Completed })
                assertEquals(1, requests.get())
            }
        }
    }

    @Test fun jsonValidatorRejectsNonObjectsIncompleteObjectsAndRawNul() {
        for (body in listOf("[]", "{\"value\":", "{\"value\":\"\u0000\"}")) {
            val failure = assertThrows(AgentModelFailure::class.java) { AgentResponseFormat.parseJsonObject(body) }
            assertEquals("INVALID_JSON_RESPONSE", failure.code)
            assertFalse(failure.retryable)
            assertNull(failure.cause)
            assertEquals("", failure.diagnostic)
        }
    }

    @Test fun jsonAllowsOnlyLegalTrailingWhitespaceAndEscapedNul() {
        for (provider in providers) withBody(providerJson(provider).toString() + " \t\r\n", "text/event-stream") { url, requests ->
            val events = mutableListOf<ProviderEvent>()
            val assistant = complete(provider, url, events)
            assertEquals("answer", assistant.getString("content"))
            assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)
            assertEquals(1, requests.get())
        }
        assertEquals("\u0000", AgentResponseFormat.parseJsonObject("{\"value\":\"\\u0000\"} \t\r\n").getString("value"))
    }

    @Test fun httpRejectionIsNotParsedAsSuccessfulJsonOrSse() {
        for (body in listOf("{\"error\":{\"message\":\"rejected\"}}", "data: [DONE]\n\n")) {
            withBody(body, "text/event-stream", status = 403) { url, requests ->
                val events = mutableListOf<ProviderEvent>()
                val error = assertThrows(AgentModelFailure::class.java) { complete(OpenAiChatCompletionsProvider, url, events) }
                assertEquals("HTTP_403", error.code)
                assertTrue(events.none { it is ProviderEvent.Completed || it is ProviderEvent.BlockDelta })
                assertEquals(1, requests.get())
            }
        }
    }

    @Test fun errorEnvelopesAreNeverSuccessfulEvenWithWrongMime() {
        for (provider in providers) for (sse in listOf(false, true)) {
            val envelope = JSONObject().put("type", "error").put("error", JSONObject()
                .put("type", "invalid_request_error").put("message", "rejected"))
            val body = if (sse) frame(envelope) else envelope.toString()
            withBody(body, if (sse) "application/json" else "text/event-stream") { url, requests ->
                val events = mutableListOf<ProviderEvent>()
                val error = assertThrows(AgentModelFailure::class.java) { complete(provider, url, events) }
                assertEquals("PROVIDER_STREAM_ERROR", error.code)
                assertTrue(events.none { it is ProviderEvent.Completed })
                assertEquals(1, requests.get())
            }
        }
    }

    @Test fun failedAndIncompleteResponsesJsonKeepTheirTerminalSemantics() {
        withBody(JSONObject().put("status", "failed").put("error", JSONObject().put("message", "failed")).toString(), "text/event-stream") { url, _ ->
            assertThrows(AgentModelFailure::class.java) { complete(OpenAiResponsesProvider, url) }
        }
        withBody(JSONObject().put("status", "incomplete").put("output", JSONArray())
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens")).toString(), "text/event-stream") { url, _ ->
            assertEquals("length", complete(OpenAiResponsesProvider, url).getString("finish_reason"))
        }
    }

    @Test fun arbitraryTextContainingDataAndInvalidJsonAreNotSwallowed() {
        for (body in listOf("upstream failed; data: not an event", "<html>data: failure</html>", "{}", "{broken")) {
            for (provider in providers) withBody(body, "text/event-stream") { url, requests ->
                val events = mutableListOf<ProviderEvent>()
                assertNotNull(runCatching { complete(provider, url, events) }.exceptionOrNull())
                assertTrue(events.none { it is ProviderEvent.Completed })
                assertEquals(1, requests.get())
            }
        }
    }

    @Test fun sseBomWhitespaceCommentsLineEndingsMultilineAndByteChunks() {
        for (newline in listOf("\n", "\r", "\r\n")) {
            val body = "\uFEFF \t$newline: heartbeat$newline$newline" +
                "id: local${newline}event: chunk${newline}data: one${newline}data: two$newline$newline"
            withServer { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    for (byte in body.toByteArray(Charsets.UTF_8)) { output.write(byte.toInt()); output.flush() }
                }
            }.use { fixture ->
                val events = mutableListOf<Triple<String?, String?, String>>()
                AgentSseClient.collect(Request.Builder().url(fixture.url).build(), AgentRunController(),
                    onEvent = { id, type, data -> events += Triple(id, type, data); finish() },
                    onJson = { fail("SSE must not be delivered as JSON") })
                assertEquals(listOf(Triple("local", "chunk", "one\ntwo")), events)
                assertEquals(1, fixture.requests.get())
            }
        }
    }

    @Test fun shortMislabelledSseIsIncrementalAndCallbackBackpressureIsPreserved() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val serverFinished = CountDownLatch(1)
        withServer { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write("data: first\n\ndata: second\n\n".toByteArray()); output.flush()
                serverFinished.await(10, TimeUnit.SECONDS) // keep HTTP body open, not a complete buffered response
            }
        }.use { fixture ->
            val events = Collections.synchronizedList(mutableListOf<String>())
            val failure = AtomicReference<Throwable?>()
            val controller = AgentRunController()
            val worker = thread(isDaemon = true) {
                try {
                    AgentSseClient.collect(Request.Builder().url(fixture.url).build(), controller,
                        onEvent = { _, _, data ->
                            events += data
                            if (data == "first") { callbackEntered.countDown(); releaseCallback.await(10, TimeUnit.SECONDS) }
                            else finish()
                        })
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                assertTrue("short keepalive SSE must emit before EOF", callbackEntered.await(3, TimeUnit.SECONDS))
                assertEquals(listOf("first"), events.toList())
                releaseCallback.countDown()
                worker.join(3_000)
                assertFalse(worker.isAlive)
                assertNull(failure.get())
                assertEquals(listOf("first", "second"), events.toList())
                assertEquals(1, fixture.requests.get())
            } finally {
                releaseCallback.countDown(); serverFinished.countDown(); controller.cancel(); worker.join(2_000)
            }
        }
    }

    @Test fun cancellationTerminatesUndecidedPrefixAndIncompleteJsonRead() {
        for (prefix in listOf("da", "{\"choices\":")) {
            val bodySent = CountDownLatch(1)
            val releaseServer = CountDownLatch(1)
            withServer { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    output.write(prefix.toByteArray()); output.flush(); bodySent.countDown()
                    releaseServer.await(10, TimeUnit.SECONDS)
                }
            }.use { fixture ->
                val controller = AgentRunController()
                val failure = AtomicReference<Throwable?>()
                val worker = thread(isDaemon = true) {
                    try {
                        AgentSseClient.collect(Request.Builder().url(fixture.url).build(), controller,
                            onEvent = { _, _, _ -> fail("partial body cannot emit a frame") },
                            onJson = { fail("incomplete JSON must not be emitted") })
                    } catch (error: Throwable) { failure.set(error) }
                }
                try {
                    assertTrue(bodySent.await(3, TimeUnit.SECONDS))
                    controller.cancel()
                    worker.join(3_000)
                    assertFalse(worker.isAlive)
                    assertTrue(failure.get() is AgentRunCancelledException)
                    assertEquals(1, fixture.requests.get())
                } finally { releaseServer.countDown(); controller.cancel(); worker.join(2_000) }
            }
        }
    }

    @Test fun jsonCallbacksNeverUseNetworkFailureRecovery() {
        for (phase in listOf("open", "json")) for (newFailure in callbackFailureFactories) {
            withBody("{}", "text/event-stream") { url, requests ->
                val expected = newFailure()
                val ignoreCalls = AtomicInteger()
                val jsonCalls = AtomicInteger()
                val actual = runCatching {
                    AgentSseClient.collect(Request.Builder().url(url).build(), AgentRunController(),
                        onOpen = { if (phase == "open") throw expected },
                        onEvent = { _, _, _ -> fail("JSON must not be delivered as SSE") },
                        onJson = { jsonCalls.incrementAndGet(); throw expected },
                        shouldIgnoreFailure = { ignoreCalls.incrementAndGet(); true })
                }.exceptionOrNull()
                assertSame(expected, actual)
                assertEquals(0, ignoreCalls.get())
                assertEquals(if (phase == "open") 0 else 1, jsonCalls.get())
                assertEquals(1, requests.get())
            }
        }
    }

    @Test fun jsonProviderCallbackFailureIsPropagatedWithoutReplay() {
        for (provider in providers) for (phase in listOf("open", "json")) for (newFailure in callbackFailureFactories) {
            withBody(providerJson(provider).toString(), "text/event-stream") { url, requests ->
                val events = mutableListOf<ProviderEvent>()
                val expected = newFailure()
                val actual = runCatching {
                    provider.complete(providerRequest(provider, url), AgentRunController()) { event ->
                        events += event
                        if ((phase == "open" && event is ProviderEvent.ResponseHeaders) ||
                            (phase == "json" && event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TEXT)) {
                            throw expected
                        }
                    }
                }.exceptionOrNull()
                assertSame(expected, actual)
                assertTrue(events.none { it is ProviderEvent.Completed })
                assertEquals(1, requests.get())
            }
        }
    }

    private fun complete(provider: AgentProviderClient, url: String, events: MutableList<ProviderEvent> = mutableListOf(), stream: Boolean = true): JSONObject =
        provider.complete(providerRequest(provider, url, stream), AgentRunController()) { events += it }.assistantMessage

    private fun providerRequest(provider: AgentProviderClient, url: String, stream: Boolean = true) = ProviderRequest(
        config = AgentModelClient.ModelConfig(baseUrl = url, apiKey = "", model = "local-model", systemPrompt = "test",
            providerSourceType = "custom", openAiEndpointMode = if (provider == OpenAiResponsesProvider) OpenAiEndpointMode.RESPONSES else OpenAiEndpointMode.CHAT_COMPLETIONS,
            customBody = listOf(CustomBody("stream", JsonPrimitive(stream)))),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")), tools = JSONArray(),
    )

    private fun providerJson(provider: AgentProviderClient): JSONObject = when (provider) {
        OpenAiChatCompletionsProvider -> JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", "answer")).put("finish_reason", "stop")))
        OpenAiResponsesProvider -> responsesSnapshot()
        AnthropicMessagesProvider -> JSONObject().put("type", "message").put("stop_reason", "end_turn")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "answer")))
        else -> error("Unknown test provider")
    }

    private fun chatCall() = JSONObject().put("id", "call_local").put("type", "function")
        .put("function", JSONObject().put("name", "inspect").put("arguments", "{}"))
    private fun chatUsage() = JSONObject().put("prompt_tokens", 4).put("completion_tokens", 3).put("total_tokens", 7)
    private fun responsesSnapshot() = JSONObject().put("status", "completed").put("usage", JSONObject().put("input_tokens", 4).put("output_tokens", 3))
        .put("output", JSONArray()
            .put(JSONObject().put("type", "reasoning").put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "thought"))))
            .put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "answer"))))
            .put(JSONObject().put("type", "function_call").put("id", "item_local").put("call_id", "call_local").put("name", "inspect").put("arguments", "{}")))
    private fun frame(json: JSONObject) = "data: $json\n\n"

    private fun assertAssistant(message: JSONObject, text: String, reasoning: String, reason: String) {
        assertEquals(text, message.getString("content")); assertEquals(reasoning, message.getString("reasoning_content"))
        assertEquals(reason, message.getString("finish_reason"))
        val call = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_local", call.getString("id")); assertEquals("inspect", call.getJSONObject("function").getString("name"))
        assertEquals("{}", call.getJSONObject("function").getString("arguments"))
    }
    private fun assertBlocks(events: List<ProviderEvent>, text: String, reasoning: String, arguments: String) {
        val ends = events.filterIsInstance<ProviderEvent.BlockEnd>()
        assertEquals(text, ends.single { it.kind == AssistantBlockKind.TEXT }.content)
        assertEquals(reasoning, ends.single { it.kind == AssistantBlockKind.THINKING }.content)
        assertEquals(arguments, ends.single { it.kind == AssistantBlockKind.TOOL_CALL }.content)
        assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)
        assertEquals(1, events.filterIsInstance<ProviderEvent.ResponseHeaders>().size)
    }

    private fun withBody(body: String, mime: String?, status: Int = 200, block: (String, AtomicInteger) -> Unit) {
        withServer { exchange ->
            mime?.let { exchange.responseHeaders.add("Content-Type", it) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }.use { block(it.url, it.requests) }
    }
    private fun withServer(handler: (HttpExchange) -> Unit): Fixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        server.executor = executor
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            try { handler(exchange) } finally { exchange.close() }
        }
        server.start()
        return Fixture("http://127.0.0.1:${server.address.port}", requests) { server.stop(0); executor.shutdownNow() }
    }
    private class Fixture(val url: String, val requests: AtomicInteger, val stop: () -> Unit) : AutoCloseable {
        override fun close() = stop()
    }
    companion object {
        private val mimeTypes = listOf("application/json", "text/event-stream", "text/plain", null)
        private val providers = listOf(OpenAiChatCompletionsProvider, OpenAiResponsesProvider, AnthropicMessagesProvider)
        private val callbackFailureFactories = listOf<() -> Throwable>(
            { IOException("callback failed") },
            { RuntimeException("callback failed") },
            { AssertionError("callback failed") },
        )
    }
}
