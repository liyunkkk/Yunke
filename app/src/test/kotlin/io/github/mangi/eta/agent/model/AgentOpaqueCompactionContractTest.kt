package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentOpaqueCompactionContractTest {
    private val session = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "session",
        systemPrompt = "", contextWindow = 500_000, openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
    )
    private fun history(): JSONArray = JSONArray().also { messages ->
        messages.put(JSONObject().put("role", "user").put("content", "task"))
        repeat(57) { index ->
            val call = "c$index"
            val assistant = JSONObject().put("role", "assistant").put("content", "note")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", call).put("type", "function")
                    .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}"))))
            ResponsesEphemeralState.attachOutputItems(assistant, JSONArray().put(
                JSONObject().put("type", "reasoning").put("id", "rs$index").put("encrypted_content", "PRIVATE_CIPHER_$index")))
            ResponsesReasoningState.capture(assistant, session)
            messages.put(assistant.put("raw_keep_sentinel", "original-$index"))
            messages.put(JSONObject().put("role", "tool").put("tool_call_id", call).put("content", "ok"))
        }
    }
    private fun dto(messages: JSONArray) = (0 until messages.length()).map {
        AgentConversationCodec.fromJsonObject(messages.getJSONObject(it))
    }
    private fun summary(): String = "[Conversation summary]\n" +
        AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- (none)" } + "\n- " + "z".repeat(32_000)
    private val provider = object : AgentProviderClient {
        override val id = "opaque-summary-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            ProviderResponse(JSONObject().put("role", "assistant").put("content", summary()).put("finish_reason", "stop"))
    }
    private fun settings(source: AgentModelClient.ModelConfig? = session) = AgentContextCompactor.Config(
        keepRecentMessages = 0,
        compressModelConfig = session.copy(model = "summarizer", contextWindow = 1_000_000,
            openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS),
        summaryProvider = provider,
        sourceModelConfig = source,
        summaryTokenBudget = AgentCompressionBoundary.summaryProgressBudget(500_000),
    )

    @Test fun compactMessagesUsesOneCutAndPreservesRawTailIdentity() {
        val messages = history()
        val before = dto(messages)
        val lastAssistant = messages.getJSONObject(messages.length() - 2)
        val lastTool = messages.getJSONObject(messages.length() - 1)
        val result = requireNotNull(AgentContextCompactor.compactMessages(
            messages, 0, 500_000, settings(), estimatedTokens = 400_000))
        assertEquals(result, dto(messages))
        assertEquals(3, result.size)
        assertSame(lastAssistant, messages.getJSONObject(messages.length() - 2))
        assertSame(lastTool, messages.getJSONObject(messages.length() - 1))
        assertEquals("original-56", messages.getJSONObject(1).getString("raw_keep_sentinel"))
        assertEquals(1, result.sumOf { AgentCompressionBoundary.replayedOpaqueItemCount(it, session) })
        assertTrue(result.sumOf { AgentContextBudget.countMessage(it).toLong() } >
            before.sumOf { AgentContextBudget.countMessage(it).toLong() })
        assertFalse(result.first().content.contains("PRIVATE_CIPHER_"))
    }

    @Test fun unknownScopeOtherModelAndChatCompletionsCannotExcuseGrowth() {
        val source = dto(history())
        for (model in listOf(null, session.copy(model = "other"),
                session.copy(openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS))) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                AgentContextCompactor.compress(source, settings(model), keepStartOverride = source.size - 2)
            }
            assertTrue(error.message.orEmpty().contains("摘要未缩小"))
        }
    }

    @Test fun noScopeDefaultsToStrictBudgetAndSmallWindowsNeverBorrowSummarizerWindow() {
        assertEquals(0, settings(null).progressBudget())
        assertEquals(0, settings().copy(summaryTokenBudget = 0).progressBudget())
        for (window in listOf(0, 1, 1024, 8192, 32_000, 500_000)) {
            val budget = AgentCompressionBoundary.summaryProgressBudget(window)
            assertTrue(budget in 0..window)
            if (window > 0) assertTrue(budget <= AgentCompressionBoundary.inputLimit(window, calibrated = false))
            assertEquals(budget, settings(session.copy(contextWindow = window)).copy(summaryTokenBudget = Int.MAX_VALUE).progressBudget())
        }
    }

    @Test fun diagnosticsOnlyCountTheResponsesTransportAndNeverRevealCiphertext() {
        val messages = history()
        assertEquals(57, AgentRequestContextDiagnostics.replayedOpaque(messages, session).first)
        assertEquals(0 to 0L, AgentRequestContextDiagnostics.replayedOpaque(messages,
            session.copy(openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS)))
        val persisted = JSONArray().also { a -> dto(messages).forEach { a.put(AgentConversationCodec.toJsonObject(it)) } }
        assertEquals(0 to 0L, AgentRequestContextDiagnostics.replayedOpaque(persisted, session.copy(model = "other")))
    }
}
