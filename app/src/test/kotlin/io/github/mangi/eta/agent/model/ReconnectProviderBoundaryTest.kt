package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Actual request bodies and TCP EOF/underfilled-body failures, without public network. */
class ReconnectProviderBoundaryTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)
    private val providers = listOf(OpenAiChatCompletionsProvider, OpenAiResponsesProvider, AnthropicMessagesProvider)
    private val policies = listOf(ErrorReconnectPolicy.WINDOW_30S, ErrorReconnectPolicy.WINDOW_1M,
        ErrorReconnectPolicy.WINDOW_5M, ErrorReconnectPolicy.CONTINUOUS)

    @Test fun enabledPoliciesNeverAcceptPartialTextOrReasoningAtEofOrDisconnect() {
        for (provider in providers) for (policy in policies) for (reasoning in listOf(false, true)) {
            for (disconnect in listOf(false, true)) {
                withResponse(partial(provider, reasoning), disconnect) { baseUrl, _ ->
                    val events = mutableListOf<ProviderEvent>()
                    val failure = assertThrows(Exception::class.java) {
                        provider.complete(request(baseUrl, provider, policy), AgentRunController(), events::add)
                    }
                    val classified = AgentModelFailure.transport(failure)
                    assertNotNull(classified)
                    assertTrue(classified!!.code in setOf("STREAM_INCOMPLETE", "MODEL_CONNECTION_FAILED", "MODEL_TIMEOUT"))
                    assertTrue(events.none { it is ProviderEvent.Completed })
                }
            }
        }
    }

    @Test fun realTerminalEvidenceStillCompletesWithReconnectEnabled() {
        for (provider in providers) {
            withResponse(partial(provider) + terminal(provider)) { baseUrl, _ ->
                val response = provider.complete(request(baseUrl, provider, ErrorReconnectPolicy.WINDOW_30S),
                    AgentRunController(), {})
                assertEquals("partial answer", response.assistantMessage.getString("content"))
                assertFalse(response.assistantMessage.has("tool_calls"))
            }
        }
    }

    @Test fun recoveryWirePayloadStripsToolsAfterCustomBodyMerging() {
        for (provider in providers) {
            withResponse(partial(provider) + terminal(provider)) { baseUrl, captured ->
                val base = request(baseUrl, provider, ErrorReconnectPolicy.WINDOW_30S)
                val injected = JSONObject().put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
                    .put("tool_choice", "required").put("parallel_tool_calls", true)
                    .put("functions", JSONArray()).put("function_call", "auto")
                    .put("web_search_options", JSONObject()).put("mcp_servers", JSONArray())
                    .put("previous_response_id", "uncertain").put("conversation", "uncertain")
                provider.complete(base.copy(config = base.config.copy(hostedWebSearchEnabled = true,
                    extraBodyJson = injected.toString()), reconnectTextOnly = true), AgentRunController(), {})
                val body = JSONObject(captured.get())
                for (key in injected.keySet()) assertFalse("Recovery must strip $key", body.has(key))
            }
        }
    }

    @Test fun responsesDoneAndAnthropicBlockStopCannotReplaceResponseTerminal() {
        for (provider in listOf(OpenAiResponsesProvider, AnthropicMessagesProvider)) {
            val middle = if (provider == OpenAiResponsesProvider) "data: [DONE]\n\n"
                else event("content_block_stop", JSONObject().put("index", 0)) +
                    event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "end_turn")))
            withResponse(partial(provider) + middle) { baseUrl, _ ->
                assertThrows(AgentModelFailure::class.java) {
                    provider.complete(request(baseUrl, provider, ErrorReconnectPolicy.WINDOW_30S), AgentRunController(), {})
                }
            }
        }
    }

    @Test fun orphanResponsesToolDeltaPreservesTheUnsafeToolClassification() {
        val orphan = event("response.function_call_arguments.delta", JSONObject()
            .put("item_id", "orphan").put("delta", "{}"))
        withResponse(orphan) { url, _ ->
            val failure = assertThrows(AgentModelFailure::class.java) {
                OpenAiResponsesProvider.complete(request(url, OpenAiResponsesProvider, ErrorReconnectPolicy.WINDOW_30S),
                    AgentRunController(), {})
            }
            assertEquals("RESPONSES_TOOL_CALL_INCOMPLETE", failure.code)
        }
    }

    @Test fun anthropicGenericDoneIsNotAnExplicitMessageStopWhenReconnectEnabled() {
        withResponse(partial(AnthropicMessagesProvider) + "data: [DONE]\n\n") { url, _ ->
            assertThrows(AgentModelFailure::class.java) {
                AnthropicMessagesProvider.complete(request(url, AnthropicMessagesProvider, ErrorReconnectPolicy.WINDOW_30S),
                    AgentRunController(), {})
            }
        }
    }

    private fun request(url: String, provider: AgentProviderClient, policy: ErrorReconnectPolicy) = ProviderRequest(
        AgentModelClient.ModelConfig(baseUrl = url, apiKey = "test", model = "test", systemPrompt = "",
            openAiEndpointMode = if (provider == OpenAiResponsesProvider) OpenAiEndpointMode.RESPONSES
                else OpenAiEndpointMode.CHAT_COMPLETIONS,
            errorReconnectPolicy = policy.persistedValue),
        JSONArray().put(JSONObject().put("role", "user").put("content", "answer")), JSONArray())

    private fun partial(provider: AgentProviderClient, reasoning: Boolean = false): String = when (provider) {
        OpenAiChatCompletionsProvider -> "data: " + JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("index", 0).put("delta", JSONObject().put(if (reasoning) "reasoning_content" else "content", "partial answer")))) + "\n\n"
        OpenAiResponsesProvider -> event(if (reasoning) "response.reasoning_summary_text.delta" else "response.output_text.delta",
            JSONObject().put("delta", "partial answer"))
        else -> event("content_block_start", JSONObject().put("index", 0).put("content_block",
            JSONObject().put("type", if (reasoning) "thinking" else "text"))) +
            event("content_block_delta", JSONObject().put("index", 0).put("delta", JSONObject()
                .put("type", if (reasoning) "thinking_delta" else "text_delta")
                .put(if (reasoning) "thinking" else "text", "partial answer")))
    }

    private fun terminal(provider: AgentProviderClient): String = when (provider) {
        OpenAiChatCompletionsProvider -> "data: " + JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("index", 0).put("delta", JSONObject()).put("finish_reason", "stop"))) + "\n\n"
        OpenAiResponsesProvider -> event("response.completed", JSONObject().put("response",
            JSONObject().put("status", "completed").put("output", JSONArray())))
        else -> event("content_block_stop", JSONObject().put("index", 0)) +
            event("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "end_turn"))) +
            event("message_stop", JSONObject())
    }

    private fun event(type: String, body: JSONObject): String =
        "event: $type\ndata: ${body.put("type", type)}\n\n"

    private fun withResponse(body: String, disconnect: Boolean = false,
        block: (String, AtomicReference<String>) -> Unit) {
        val captured = AtomicReference("")
        val failure = AtomicReference<Throwable?>()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "reconnect-boundary-test").apply { isDaemon = true } }
        val worker = executor.submit {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().buffered()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        check(header.length < 32 * 1024)
                        val byte = input.read()
                        check(byte >= 0)
                        header.append(byte.toChar())
                    }
                    val length = header.toString().lineSequence().firstOrNull {
                        it.startsWith("Content-Length:", ignoreCase = true)
                    }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    check(length in 0..1_048_576)
                    val requestBytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(requestBytes, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    captured.set(String(requestBytes, Charsets.UTF_8))
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                        "Content-Length: ${bytes.size + if (disconnect) 64 else 0}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                    socket.shutdownOutput()
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        try {
            block("http://127.0.0.1:${server.localPort}", captured)
            worker.get(5, TimeUnit.SECONDS)
            assertNull("TCP fixture must deliver without error", failure.get())
        } finally {
            server.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
