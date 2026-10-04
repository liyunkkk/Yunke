package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure counting and bounded-correlation coverage. These tests never build Android, but they do
 * drive the real [AgentLoop] with a fake provider so the request-context record is exercised
 * end-to-end without network access.
 */
class AgentRequestContextDiagnosticsTest {
    private val config = AgentModelClient.ModelConfig(
        providerSourceType = "custom",
        baseUrl = "https://example.com/v1",
        apiKey = "not-persisted",
        model = "gpt-5.4",
        systemPrompt = "SYSTEM_SENTINEL",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
    )

    @Test fun finalBodyCountsTypesAndCiphertextVolumeWithoutTokenizingSecrets() {
        val ciphertext = "CIPHER_SECRET_0123456789"
        val live = JSONObject().put("role", "assistant").put("content", "ANSWER_SECRET")
            .put("tool_calls", JSONArray().put(
                JSONObject().put("id", "call_1").put("type", "function").put(
                    "function", JSONObject().put("name", "terminal")
                        .put("arguments", """{"command":"ARGUMENT_SECRET"}"""),
                ),
            ))
        ResponsesEphemeralState.attachOutputItems(live, JSONArray()
            .put(JSONObject().put("type", "reasoning").put("id", "rs_1").put("encrypted_content", ciphertext))
            .put(JSONObject().put("type", "message").put("role", "assistant").put("content", "EPHEMERAL_SECRET")))
        ResponsesReasoningState.capture(live, config)

        val messages = JSONArray()
            .put(AgentConversationCodec.userTextMessage("USER_SECRET"))
            .put(live)
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "RESULT_SECRET"))
        val tools = JSONArray().put(
            AgentToolSchema.function("terminal", "DESCRIPTION_SECRET", JSONObject().put("type", "object")),
        )
        val request = ResponsesRequestBuilder.build(config, messages, tools)

        val body = AgentRequestContextDiagnostics.responseBody(request, messages)
        assertEquals(messages.length(), body.sourceMessages)
        assertEquals(request.getJSONArray("input").length(), body.inputItems)
        assertEquals(4, body.inputItems)
        assertEquals(1, body.ephemeralMessages)
        assertEquals(2, body.ephemeralItems)
        assertEquals(1, body.inputReasoningItems)
        assertEquals(2, body.inputMessageItems)
        assertEquals(1, body.inputFunctionCallOutputItems)
        assertEquals(0, body.inputFunctionCallItems)
        assertEquals(ciphertext.length.toLong(), body.encryptedContentChars)
        assertEquals(1, body.toolCount)
        assertTrue(body.instructionsTokens > 0)

        val text = AgentRequestContextDiagnostics.responseBodyFields(body).toString()
        for (secret in listOf(
            ciphertext, "USER_SECRET", "RESULT_SECRET", "ANSWER_SECRET", "ARGUMENT_SECRET",
            "EPHEMERAL_SECRET", "DESCRIPTION_SECRET", "SYSTEM_SENTINEL",
        )) {
            assertFalse("payload leaked into diagnostic fields: $secret", text.contains(secret))
        }
    }

    @Test fun countsMatchTheFinalProjectionAndMutateNothing() {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("hello"))
        val tools = JSONArray().put(
            AgentToolSchema.function("terminal", "read", JSONObject().put("type", "object")),
        )
        val request = ResponsesRequestBuilder.build(config, messages, tools)
        val requestBefore = request.toString()
        val messagesBefore = messages.toString()

        val body = AgentRequestContextDiagnostics.responseBody(request, messages)
        assertEquals(request.getString("instructions").length, body.instructionsChars)
        assertEquals(request.getJSONArray("input").length(), body.inputItems)
        assertEquals(request.optJSONArray("tools")?.length() ?: 0, body.toolCount)
        assertEquals(messages.length(), body.sourceMessages)
        assertEquals(requestBefore, request.toString())
        assertEquals(messagesBefore, messages.toString())
        // Same round, same snapshot -> identical projection.
        assertEquals(body, AgentRequestContextDiagnostics.responseBody(request, messages))
    }

    @Test fun localFieldsStayNumericAndLabelEstimatesAndCloudReceipt() {
        val fields = AgentRequestContextDiagnostics.localRequestFields(
            AgentRequestContextDiagnostics.LocalRequest(
                sourceMessages = 5, systemCount = 1, boundaryTokens = 100, rawHistoryTokens = 90,
                fixedTokens = 20, filteredTokens = 110, filteredBasis = "published_boundary",
                cloudInput = 97_498, cloudCached = 12_000, cloudCacheCreation = 700, cloudOutput = 50,
            ),
        )
        assertEquals(5, fields.getInt("source_messages"))
        assertEquals(110, fields.getInt("filtered_tokens_est"))
        assertEquals("published_boundary", fields.getString("filtered_basis"))
        assertEquals(97_498, fields.getInt("cloud_input"))
        assertEquals(12_000, fields.getInt("cloud_cached"))
        assertEquals(700, fields.getInt("cloud_cache_creation"))
        assertEquals(50, fields.getInt("cloud_output"))
    }

    @Test fun absentFilteredAndCloudValuesAreOmittedNotZero() {
        val fields = AgentRequestContextDiagnostics.localRequestFields(
            AgentRequestContextDiagnostics.LocalRequest(
                sourceMessages = 3, systemCount = 0, boundaryTokens = 10, rawHistoryTokens = 9,
                fixedTokens = 1, filteredTokens = null, filteredBasis = "unavailable",
            ),
        )
        assertFalse(fields.has("filtered_tokens_est"))
        assertFalse(fields.has("cloud_input"))
        assertFalse(fields.has("cloud_cached"))
        assertFalse(fields.has("cloud_cache_creation"))
        assertFalse(fields.has("cloud_output"))
        assertEquals("unavailable", fields.getString("filtered_basis"))
    }

    @Test fun loopEmitsOneRequestContextRecordCorrelatedWithTheSameRoundCloudReceipt() {
        val logs = mutableListOf<String>()
        val diagnostics = AgentToolCallDiagnostics(enabled = { true }, sink = { logs += it })
        val provider = object : AgentProviderClient {
            override val id = "request-context-test"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                onEvent(ProviderEvent.Usage(AgentTokenUsage(
                    inputTokens = 97_498, cachedTokens = 12_000,
                    cacheCreationTokens = 700, outputTokens = 50,
                )))
                return ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"),
                )
            }
        }
        AgentLoop(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1", apiKey = "not-persisted", model = "test",
                systemPrompt = "SYSTEM_SECRET", contextWindow = 1_000_000,
            ),
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("SECRET_USER_TEXT")),
            tools = JSONArray(),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{}") },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            toolDiagnostics = diagnostics,
        ).run()

        val context = logs.map { JSONObject(it.removePrefix("ToolCallDiag ")) }
            .single { it.optString("stage") == "request_context" }
        assertEquals(97_498, context.getInt("cloud_input"))
        assertEquals(12_000, context.getInt("cloud_cached"))
        assertEquals(700, context.getInt("cloud_cache_creation"))
        assertEquals(50, context.getInt("cloud_output"))
        assertEquals(1, context.getInt("source_messages"))
        assertEquals(1, context.getInt("round"))
        assertTrue(context.has("filtered_tokens_est"))
        assertTrue(context.has("boundary_tokens_est"))
        assertTrue(context.has("fixed_tokens_est"))

        val text = logs.joinToString("\n")
        for (secret in listOf("SECRET_USER_TEXT", "SYSTEM_SECRET", "not-persisted")) {
            assertFalse("secret leaked: $secret", text.contains(secret))
        }
    }
}
