package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentWireRequestEstimateTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1",
        apiKey = "TEST_KEY_NOT_REAL", model = "test", systemPrompt = "")
    private fun config(endpoint: EndpointKind) = config.copy(
        providerType = if (endpoint == EndpointKind.ANTHROPIC_MESSAGES) ProviderTypes.ANTHROPIC else ProviderTypes.OPENAI_COMPATIBLE,
        openAiEndpointMode = if (endpoint == EndpointKind.RESPONSES) OpenAiEndpointMode.RESPONSES else OpenAiEndpointMode.CHAT_COMPLETIONS)
    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun body(c: AgentModelClient.ModelConfig, m: JSONArray, t: JSONArray = JSONArray()) =
        AgentWireRequestEstimate.previewBody(c, m, t)
    private fun shape(c: AgentModelClient.ModelConfig, b: JSONObject) =
        AgentWireRequestEstimate.measure(b, AgentWireRequestEstimate.endpoint(c))
    private fun custom(key: String, value: String) = CustomBody(key, Json.parseToJsonElement(value))

    @Test fun onlySentPlaintextReasoningCountsAndOpaqueLengthNeverBecomesTokens() {
        val plain = JSONArray().put(msg("user", "hello")).put(msg("assistant", "answer"))
        val withReasoning = JSONArray(plain.toString()).also {
            it.getJSONObject(1).put("reasoning_content", "UNSENT_REASONING".repeat(1000))
        }
        for (endpoint in EndpointKind.entries) {
            val c = config(endpoint)
            val original = shape(c, body(c, plain))
            val replay = shape(c, body(c, withReasoning))
            if (endpoint == EndpointKind.CHAT_COMPLETIONS) assertTrue(replay.tokens > original.tokens)
            else assertEquals(original.tokens, replay.tokens)
        }
        val c = config(EndpointKind.RESPONSES)
        fun opaque(chars: Int): AgentWireRequestEstimate.Shape {
            val m = JSONArray(plain.toString())
            ResponsesEphemeralState.attachOutputItems(m.getJSONObject(1), JSONArray()
                .put(JSONObject().put("type", "reasoning").put("encrypted_content", "X".repeat(chars))))
            return shape(c, body(c, m))
        }
        assertEquals(opaque(10).tokens, opaque(100_000).tokens)
        assertEquals(1, opaque(100_000).opaqueItems)
        assertEquals(100_000L, opaque(100_000).encryptedChars)
    }

    @Test fun finalOverridesAndHostedDefinitionsRatherThanSourceArraysArePriced() {
        val source = JSONArray().put(msg("system", "SOURCE_SYSTEM")).put(msg("user", "SOURCE_TEXT".repeat(100)))
        val replacement = """[{"role":"user","content":"sent"}]"""
        val tools = """[{"type":"function","function":{"name":"replacement","parameters":{"type":"object"}}}]"""
        val chat = config(EndpointKind.CHAT_COMPLETIONS).copy(
            extraBodyJson = """{"messages":[{"role":"user","content":"extra"}],"tools":[]} """,
            customBody = listOf(custom("messages", replacement), custom("tools", tools)))
        val final = body(chat, source)
        val estimate = shape(chat, final)
        assertEquals(4L, estimate.text.chars)
        assertEquals(0L, estimate.instructions.chars)
        assertEquals(AgentContextBudget.countTokens(final.getJSONArray("tools").toString()), estimate.tools.tokens)

        val anthropic = config(EndpointKind.ANTHROPIC_MESSAGES).copy(
            extraBodyJson = """{"system":"NOT_APPLICABLE"}""",
            customBody = listOf(custom("system", "\"CUSTOM_SYSTEM\""), custom("messages", replacement),
                custom("tools", """[{"name":"x","input_schema":{"type":"object"}}]""")))
        val a = shape(anthropic, body(anthropic, source))
        assertEquals("CUSTOM_SYSTEM".length.toLong(), a.instructions.chars)
        assertEquals(4L, a.text.chars)
        assertEquals(1, a.toolCount)

        // Responses owns instructions/input/tools after merging custom/extra; don't price rejected overrides.
        val responses = config(EndpointKind.RESPONSES).copy(hostedWebSearchEnabled = true,
            customBody = listOf(custom("instructions", "\"NOT_SENT\""), custom("input", "[]"), custom("tools", "[]")))
        val r = shape(responses, body(responses, source))
        assertEquals("SOURCE_SYSTEM".length.toLong(), r.instructions.chars)
        assertEquals(1, r.hostedToolCount)
        assertTrue(r.tools.tokens > 0)
    }

    @Test fun allProtocolsPublishExactlyTheFinalBodyEstimateAndRedactedNumericDiagnostics() {
        for (endpoint in EndpointKind.entries) {
            val c = config(endpoint)
            val logs = mutableListOf<String>()
            val diagnostics = AgentToolCallDiagnostics(enabled = { true }, sink = { logs += it })
            val m = JSONArray().put(msg("system", "SYSTEM_SECRET中文")).put(msg("user", "USER_SECRET"))
            val t = JSONArray().put(AgentToolSchema.function("tool", "SCHEMA_SECRET", JSONObject().put("type", "object")))
            val request = ProviderRequest(c, m, t, toolDiagnosticAttempt = diagnostics.beginAttempt(1, "test"))
            val b = body(c, m, t)
            val before = b.toString()
            val events = mutableListOf<ProviderEvent>()
            AgentWireRequestEstimate.publish(b, endpoint, request, events::add)
            assertEquals(shape(c, b).tokens, (events.single() as ProviderEvent.RequestEstimate).tokens)
            assertEquals(before, b.toString())
            val record = logs.map { JSONObject(it.removePrefix("ToolCallDiag ")) }.single { it.optString("stage") == "request_shape" }
            assertEquals(shape(c, b).tokens, record.getInt("request_tokens_est"))
            for (field in listOf("instructions", "text", "reasoning_text", "tool_calls", "tool_results", "tool_schema", "format", "media")) {
                assertTrue(record.get("${field}_chars") is Number)
                assertTrue(record.get("${field}_utf8_bytes") is Number)
                assertTrue(record.get("${field}_tokens_est") is Number)
            }
            for (secret in listOf("SYSTEM_SECRET", "USER_SECRET", "SCHEMA_SECRET", c.apiKey)) assertFalse(logs.joinToString().contains(secret))
        }
    }

    @Test fun anthropicBodyIsFreshSystemIsNotDuplicatedAndAppendingUserDoesNotNestOldBody() {
        val c = config(EndpointKind.ANTHROPIC_MESSAGES)
        val m = JSONArray().put(msg("system", "UNIQUE_SYSTEM")).put(msg("user", "first"))
        val before = m.toString()
        val first = body(c, m)
        val second = body(c, m)
        assertEquals(before, m.toString())
        assertEquals(first.toString(), second.toString())
        assertEquals("UNIQUE_SYSTEM", first.getString("system"))
        assertFalse(first.getJSONArray("messages").toString().contains("UNIQUE_SYSTEM"))
        m.put(msg("user", "short"))
        val appended = body(c, m)
        assertEquals(5L, shape(c, appended).text.chars - shape(c, first).text.chars)
        assertEquals(shape(c, first).instructions, shape(c, appended).instructions)
        assertEquals(shape(c, first).tools, shape(c, appended).tools)
        assertFalse(appended.getJSONArray("messages").toString().contains("max_tokens"))
    }

    @Test fun mediaBase64IsCountedAsMediaNotPlainTextAndRemoteAnthropicImagesAreDropped() {
        val image = JSONObject().put("type", "image_url").put("image_url", JSONObject()
            .put("url", "data:image/png;base64," + "A".repeat(16_384)))
        val m = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(image)))
        for (endpoint in EndpointKind.entries) {
            val c = config(endpoint)
            val s = shape(c, body(c, m))
            assertEquals(1, s.imageCount)
            assertEquals(16_384L, s.media.chars)
            assertEquals(16_384L, s.media.utf8Bytes)
            assertEquals(85, s.media.tokens)
            assertEquals(0, s.text.tokens)
        }
        image.getJSONObject("image_url").put("url", "https://example.invalid/image.png")
        val c = config(EndpointKind.ANTHROPIC_MESSAGES)
        assertEquals(0, shape(c, body(c, m)).imageCount)
    }

    @Test fun initialLoopProjectionUsesFinalBodyNotThePreProtocolBoundary() {
        val events = mutableListOf<AgentEvent>()
        val c = config(EndpointKind.RESPONSES)
        val m = JSONArray().put(msg("user", "hello")).put(msg("assistant", "answer")
            .put("reasoning_content", "NOT_SENT".repeat(1000)))
        var expected = 0
        val provider = object : AgentProviderClient {
            override val id = "wire-estimate-test"
            override val capabilities = ProviderCapabilities(EndpointKind.RESPONSES, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val b = body(request.config, request.messages, request.tools)
                expected = shape(request.config, b).tokens
                onEvent(ProviderEvent.RequestStarted)
                AgentWireRequestEstimate.publish(b, capabilities.endpoint, request, onEvent)
                return ProviderResponse(msg("assistant", "done").put("finish_reason", "stop"))
            }
        }
        AgentLoop(config = c, messages = m, tools = JSONArray(), provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{}") },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(), onEvent = events::add).run()
        val projected = events.filterIsInstance<AgentEvent.UsageReceived>().single()
        assertTrue(projected.projected)
        assertEquals(expected, projected.usage.inputTokens)
        assertTrue(expected < AgentRequestTokenEstimate.boundary(m, JSONArray(), false, false))
    }
}
