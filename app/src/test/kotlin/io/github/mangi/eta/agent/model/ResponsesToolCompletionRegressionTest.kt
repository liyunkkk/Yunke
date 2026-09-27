package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Provider/retry boundary only: no terminal backend or real tool execution. */
class ResponsesToolCompletionRegressionTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    @Test fun validToolArgumentsAtEofCannotExecuteOrReplay() {
        assertRejectedWithoutExecution(toolDeltas(), "RESPONSES_TOOL_CALL_INCOMPLETE")
    }

    @Test fun disconnectAfterValidToolArgumentsCannotExecuteOrReplay() {
        assertRejectedWithoutExecution(
            toolDeltas(), "RESPONSES_TOOL_CALL_INCOMPLETE", disconnect = true,
        )
    }

    @Test fun doneMarkerIsNotResponseCompletionEvidenceForTools() {
        assertRejectedWithoutExecution(
            toolDeltas() + "data: [DONE]\n\n", "RESPONSES_TOOL_CALL_INCOMPLETE",
        )
    }

    @Test fun itemAndArgumentsDoneWithoutResponseCompletionCannotExecute() {
        val body = toolDeltas() +
            event("response.function_call_arguments.done", JSONObject()
                .put("item_id", ITEM_ID).put("arguments", VALID_ARGUMENTS)) +
            event("response.output_item.done", JSONObject()
                .put("output_index", 0).put("item", functionItem(VALID_ARGUMENTS)))
        assertRejectedWithoutExecution(body, "RESPONSES_TOOL_CALL_INCOMPLETE")
    }

    @Test fun visibleTextDoesNotMakeAnUnfinishedToolRecoverable() {
        val text = event("response.output_text.delta", JSONObject().put("delta", "checking"))
        assertRejectedWithoutExecution(text + toolDeltas(), "RESPONSES_TOOL_CALL_INCOMPLETE")
    }

    @Test fun orphanToolDeltaAlsoPreventsNetworkRetry() {
        val body = event("response.function_call_arguments.delta", JSONObject()
            .put("item_id", ITEM_ID).put("delta", VALID_ARGUMENTS))
        assertRejectedWithoutExecution(body, "RESPONSES_TOOL_CALL_INCOMPLETE", disconnect = true)
    }

    @Test fun completedCallPreservesTerminalNameAndLinuxEnvironment() {
        val body = toolDeltas() + completed(JSONArray().put(functionItem(VALID_ARGUMENTS)))
        withSseServer(body) { baseUrl, requests ->
            val response = complete(baseUrl)
            val call = onlyCall(response)
            assertEquals("tool_calls", response.assistantMessage.getString("finish_reason"))
            assertEquals("terminal", call.name)
            assertEquals("linux", JSONObject(call.argumentsJson).getString("environment"))
            assertEquals("pwd", JSONObject(call.argumentsJson).getString("command"))
            assertNull(AgentToolCallValidator(tools()).validate(call))
            assertEquals(1, requests.get())
        }
    }

    @Test fun nonemptyFinalOutputRejectsMissingNullAndBlankArguments() {
        for (arguments in listOf<Any?>(null, JSONObject.NULL, "", " \t\r\n ")) {
            val body = toolDeltas() + completed(JSONArray().put(functionItem(arguments)))
            assertRejectedWithoutExecution(body, "RESPONSES_TOOL_ARGUMENTS_INCOMPLETE")
        }
    }

    @Test fun missingFinalArgumentsAreRejectedEvenWithoutPriorDeltas() {
        // No BlockStart/Delta is available for the retry guard: classification itself must stop replay.
        assertRejectedWithoutExecution(
            completed(JSONArray().put(functionItem())), "RESPONSES_TOOL_ARGUMENTS_INCOMPLETE",
        )
    }

    @Test fun explicitEmptyObjectReachesNormalSchemaValidation() {
        val body = toolDeltas() + completed(JSONArray().put(functionItem("{}")))
        withSseServer(body) { baseUrl, _ ->
            val call = onlyCall(complete(baseUrl))
            assertEquals("terminal", call.name)
            assertEquals("{}", call.argumentsJson)
            val validationError = AgentToolCallValidator(tools()).validate(call)
            assertNotNull(validationError)
            assertTrue(validationError!!.contains("environment"))
            assertFalse(JSONObject(call.argumentsJson).has("command"))
        }
    }

    @Test fun completedEmptyOutputCannotInventArgumentsForAnAddedOnlyCall() {
        for (arguments in listOf<Any?>(null, "", " \t\n")) {
            val body = event("response.output_item.added", JSONObject()
                .put("output_index", 0).put("item", functionItem(arguments))) + completed(JSONArray())
            assertRejectedWithoutExecution(body, "RESPONSES_TOOL_ARGUMENTS_INCOMPLETE")
        }
    }

    @Test fun completedEmptyOutputPreservesExplicitEmptyObject() {
        val body = event("response.output_item.added", JSONObject()
            .put("output_index", 0).put("item", functionItem("{}"))) + completed(JSONArray())
        withSseServer(body) { baseUrl, _ ->
            assertEquals("{}", onlyCall(complete(baseUrl)).argumentsJson)
        }
    }

    @Test fun completedEmptyOutputKeepsStreamCompatibility() {
        withSseServer(toolDeltas() + completed(JSONArray())) { baseUrl, _ ->
            val response = complete(baseUrl)
            val call = onlyCall(response)
            assertEquals("terminal", call.name)
            assertEquals(VALID_ARGUMENTS, call.argumentsJson)
            assertEquals("tool_calls", response.assistantMessage.getString("finish_reason"))
            assertNull(AgentToolCallValidator(tools()).validate(call))
            assertNull(ResponsesEphemeralState.outputItems(response.assistantMessage))
        }
    }

    @Test fun authoritativeFinalArgumentsNeverReviveStreamedFields() {
        for (arguments in listOf(
            """{"command":"ls"}""",
            """{"environment":"linux"}""",
            """{"environment":null,"command":"ls"}""",
            """{"environment":"linux","command":null}""",
            """{"environment":"android","command":"ls"}""",
        )) {
            val body = toolDeltas() + completed(JSONArray().put(functionItem(arguments)))
            withSseServer(body) { baseUrl, _ ->
                val call = onlyCall(complete(baseUrl))
                assertEquals("terminal", call.name)
                assertEquals(arguments, call.argumentsJson)
                assertTrue(JSONObject(arguments).similar(JSONObject(call.argumentsJson)))
            }
        }
    }

    @Test fun objectValuedFinalArgumentsRemainSupportedAndAuthoritative() {
        for (arguments in listOf(
            JSONObject(VALID_ARGUMENTS),
            JSONObject().put("command", "ls"),
            JSONObject(),
        )) {
            withSseServer(toolDeltas() + completed(JSONArray().put(functionItem(arguments)))) { baseUrl, _ ->
                val call = onlyCall(complete(baseUrl))
                assertEquals("terminal", call.name)
                assertTrue(arguments.similar(JSONObject(call.argumentsJson)))
            }
        }
    }

    @Test fun textOnlyEofAndDisconnectRetainCompatibility() {
        val body = event("response.output_text.delta", JSONObject().put("delta", "partial answer"))
        for (disconnect in listOf(false, true)) {
            withSseServer(body, disconnect) { baseUrl, requests ->
                val response = complete(baseUrl)
                assertEquals("partial answer", response.assistantMessage.getString("content"))
                assertEquals("stop", response.assistantMessage.getString("finish_reason"))
                assertFalse(response.assistantMessage.has("tool_calls"))
                assertEquals(1, requests.get())
            }
        }
    }

    @Test fun diagnosticsCorrelateRawProviderCodecAndResultWithoutLeakingPayloads() {
        val logs = mutableListOf<String>()
        val diagnostics = AgentToolCallDiagnostics(enabled = { true }, sink = { logs += it })
        val body = toolDeltas() + completed(JSONArray().put(functionItem(VALID_ARGUMENTS)))
        withSseServer(body) { baseUrl, _ ->
            val result = AgentModelRetry().complete(
                initialRound = 7,
                request = request(baseUrl).copy(toolDiagnostics = diagnostics),
                provider = OpenAiResponsesProvider,
                controller = AgentRunController(),
                onEvent = {}, onProviderEvent = { _, _ -> }, discardAttemptReasoning = {},
            )
            val call = AgentConversationCodec.parseToolCalls(result.response.assistantMessage).single()
            val attempt = requireNotNull(result.toolDiagnosticAttempt)
            attempt.parsed(call, 0)
            attempt.validation(call, true)
            attempt.dispatch(call)
            attempt.result(call, AgentModelClient.ToolResult(
                """{"ok":true,"tool":"terminal","environment":"debian","exit_code":0,"stdout":"secret-output"}""",
            ))
            val records = logs.map { JSONObject(it.removePrefix("ToolCallDiag ")) }
            val raw = records.single { it.optString("stage") == "raw_terminal" }
            val parsed = records.single { it.optString("stage") == "parsed" }
            val executed = records.single { it.optString("stage") == "result" }
            assertEquals(raw.getInt("call"), parsed.getInt("call"))
            assertEquals(raw.getInt("attempt"), executed.getInt("attempt"))
            assertEquals(raw.getString("arguments_hmac"), parsed.getString("arguments_hmac"))
            assertEquals("linux", parsed.getString("requested_environment"))
            assertEquals("debian", executed.getString("actual_environment"))
            val text = logs.joinToString("\n")
            for (secret in listOf(VALID_ARGUMENTS, "secret-output", "call_terminal", ITEM_ID, "pwd")) {
                assertFalse("diagnostics leaked a payload", text.contains(secret))
            }
        }
    }

    @Test fun diagnosticsCaptureMissingTerminalEvidenceWithoutAnotherRequest() {
        val logs = mutableListOf<String>()
        val diagnostics = AgentToolCallDiagnostics(enabled = { true }, sink = { logs += it })
        withSseServer(toolDeltas()) { baseUrl, requests ->
            val failure = runCatching {
                AgentModelRetry().complete(
                    initialRound = 1,
                    request = request(baseUrl).copy(toolDiagnostics = diagnostics),
                    provider = OpenAiResponsesProvider,
                    controller = AgentRunController(),
                    onEvent = {}, onProviderEvent = { _, _ -> }, discardAttemptReasoning = {},
                )
            }.exceptionOrNull()
            assertTrue(failure is AgentModelFailure)
            assertEquals(1, requests.get())
            val failed = logs.map { JSONObject(it.removePrefix("ToolCallDiag ")) }
                .single { it.optString("stage") == "failed" }
            assertEquals("RESPONSES_TOOL_CALL_INCOMPLETE", failed.getString("code"))
            assertTrue(failed.getBoolean("saw_tool_call"))
            assertFalse(failed.getBoolean("saw_terminal"))
        }
    }

    private fun assertRejectedWithoutExecution(body: String, expectedCode: String, disconnect: Boolean = false) {
        withSseServer(body, disconnect) { baseUrl, requests ->
            val events = mutableListOf<ProviderEvent>()
            val runEvents = mutableListOf<AgentEvent>()
            val executed = mutableListOf<AgentModelClient.ToolCall>()
            var failure: AgentModelFailure? = null
            var acceptedResponse = false
            var retryWaits = 0
            var discardedAttempts = 0
            val retry = AgentModelRetry { _, _ ->
                retryWaits += 1
                throw AssertionError("Incomplete Responses tool call must not be retried")
            }
            try {
                val result = retry.complete(
                    initialRound = 1,
                    request = request(baseUrl),
                    provider = OpenAiResponsesProvider,
                    controller = AgentRunController(),
                    onEvent = { runEvents += it },
                    onProviderEvent = { _, event -> events += event },
                    discardAttemptReasoning = { discardedAttempts += 1 },
                )
                acceptedResponse = true
                // Fake downstream executor: production may execute only after complete returns.
                val calls = result.response.assistantMessage.optJSONArray("tool_calls") ?: JSONArray()
                for (index in 0 until calls.length()) executed += toolCall(calls.getJSONObject(index))
            } catch (error: AgentModelFailure) {
                failure = error
            }
            assertNotNull("A protocol failure must replace the partial tool response", failure)
            val actual = requireNotNull(failure)
            assertEquals(expectedCode, actual.code)
            assertFalse(actual.retryable)
            assertFalse(actual.envelopeCorrectionAllowed)
            assertFalse(requireNotNull(AgentModelFailure.transport(actual)).retryable)
            assertFalse(acceptedResponse)
            assertTrue(executed.isEmpty())
            assertTrue(events.none { it is ProviderEvent.Completed })
            assertTrue(runEvents.none { it is AgentEvent.ModelRetryScheduled })
            assertEquals(0, retryWaits)
            assertEquals(0, discardedAttempts)
            assertEquals(1, requests.get())
        }
    }

    private fun complete(baseUrl: String): ProviderResponse = OpenAiResponsesProvider.complete(
        request(baseUrl), AgentRunController(), {},
    )

    private fun request(baseUrl: String) = ProviderRequest(
        config = AgentModelClient.ModelConfig(
            providerSourceType = "custom",
            baseUrl = baseUrl,
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "test",
            openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
        ),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "inspect")),
        tools = tools(),
    )

    private fun tools(): JSONArray = JSONArray().put(JSONObject().put("type", "function").put(
        "function", JSONObject().put("name", "terminal").put("parameters", JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("environment").put("command"))
            .put("properties", JSONObject()
                .put("environment", JSONObject().put("type", "string").put("enum", JSONArray().put("linux")))
                .put("command", JSONObject().put("type", "string").put("minLength", 1)))),
    ))

    private fun onlyCall(response: ProviderResponse): AgentModelClient.ToolCall {
        val calls = response.assistantMessage.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        return toolCall(calls.getJSONObject(0))
    }

    private fun toolCall(call: JSONObject): AgentModelClient.ToolCall {
        val function = call.getJSONObject("function")
        return AgentModelClient.ToolCall(
            id = call.getString("id"),
            name = function.getString("name"),
            argumentsJson = function.getString("arguments"),
        )
    }

    private fun functionItem(arguments: Any? = null): JSONObject = JSONObject()
        .put("id", ITEM_ID).put("type", "function_call")
        .put("call_id", "call_terminal").put("name", "terminal")
        .also { if (arguments != null) it.put("arguments", arguments) }

    private fun toolDeltas(): String =
        event("response.output_item.added", JSONObject().put("output_index", 0).put("item", functionItem())) +
            event("response.function_call_arguments.delta", JSONObject()
                .put("item_id", ITEM_ID).put("delta", VALID_ARGUMENTS))

    private fun completed(output: JSONArray): String = event("response.completed", JSONObject().put(
        "response", JSONObject().put("status", "completed").put("output", output),
    ))

    private fun event(type: String, fields: JSONObject): String =
        "event: $type\ndata: ${JSONObject(fields.toString()).put("type", type)}\n\n"

    // Same in-process fake SSE transport as OpenAiResponsesProviderTest. The baseline
    // has no MockWebServer dependency; do not add dependencies for these regressions.
    private fun withSseServer(
        body: String,
        disconnect: Boolean = false,
        block: (String, AtomicInteger) -> Unit,
    ) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/responses") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            // A larger declared body forces a transport failure after all tool frames
            // have been read, rather than an ordinary successful HTTP EOF.
            exchange.sendResponseHeaders(200, bytes.size.toLong() + if (disconnect) 64L else 0L)
            try {
                exchange.responseBody.write(bytes)
                exchange.responseBody.flush()
            } finally {
                try {
                    exchange.responseBody.close()
                } catch (error: IOException) {
                    if (!disconnect) throw error
                } finally {
                    exchange.close()
                }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", requests)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private companion object {
        const val ITEM_ID = "fc_terminal"
        const val VALID_ARGUMENTS = """{"environment":"linux","command":"pwd"}"""
    }
}
