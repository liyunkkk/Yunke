package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.applyModelUsageDelta
import io.github.mangi.eta.data.repository.decodeModelUsageSnapshot
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real provider -> UsageRecordingProvider -> ledger, using the provider tests' local SSE fixture. */
class OpenAiProviderRegressionTest {
    @Test
    fun chatEmptyOrUnparseableUsageFallsBackAndReachesLedger() {
        for (placeholder in listOf(JSONObject(), JSONObject().put("prompt_tokens", "unknown"))) {
            val body = frame(JSONObject().put("usage", placeholder)
                .put("response", JSONObject().put("usage", usage(100, 20, 60, 5)))) + chatCompleted()
            withSseServer(body) { baseUrl ->
                val ledger = Ledger()
                val events = mutableListOf<ProviderEvent>()
                val result = ledger.wrap(OpenAiChatCompletionsProvider).complete(
                    request(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS), AgentRunController(), events::add,
                )
                assertEquals(100, events.filterIsInstance<ProviderEvent.Usage>().single().usage.inputTokens)
                assertEquals(100, result.assistantMessage.getJSONObject("usage").getInt("input_tokens"))
                assertEquals(1, ledger.records.size)
                ledger.assertTotals(100, 20, 60, 5)
            }
        }
    }

    @Test
    fun chatValidTopLevelUsageKeepsPriorityIncludingExplicitZero() {
        for (input in listOf(100, 0)) {
            val body = frame(JSONObject().put("usage", usage(input, 0))
                .put("response", JSONObject().put("usage", usage(900, 90)))) + chatCompleted()
            withSseServer(body) { baseUrl ->
                val ledger = Ledger()
                val events = mutableListOf<ProviderEvent>()
                ledger.wrap(OpenAiChatCompletionsProvider).complete(
                    request(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS), AgentRunController(), events::add,
                )
                assertEquals(input, events.filterIsInstance<ProviderEvent.Usage>().single().usage.inputTokens)
                assertEquals(1, ledger.records.size)
                ledger.assertTotals(input.toLong(), 0)
            }
        }
    }

    @Test
    fun chatSameFrameErrorReportsAndRecordsUsageBeforeOriginalError() {
        val body = frame(JSONObject().put("usage", JSONObject())
            .put("response", JSONObject().put("usage", usage(100, 20, 60, 5)))
            .put("error", JSONObject().put("code", "billing_error").put("message", "original chat failure")))
        withSseServer(body) { baseUrl ->
            val ledger = Ledger()
            val order = mutableListOf<String>()
            val failure = assertThrows(AgentModelFailure::class.java) {
                ledger.wrap(OpenAiChatCompletionsProvider).complete(
                    request(baseUrl, OpenAiEndpointMode.CHAT_COMPLETIONS), AgentRunController(),
                ) { event ->
                    if (event is ProviderEvent.Usage) order += "usage"
                    assertFalse(event is ProviderEvent.Completed)
                }
            }
            order += "error"
            assertEquals(listOf("usage", "error"), order)
            assertEquals("PROVIDER_STREAM_ERROR", failure.code)
            assertFalse(failure.retryable)
            assertEquals("模型接口 SSE 返回错误 (code=billing_error)：original chat failure", failure.message)
            assertEquals(1, ledger.records.size)
            ledger.assertTotals(100, 20, 60, 5)
        }
    }

    @Test
    fun responsesSelectsFirstParseableCandidateWithoutChangingPriority() {
        // All candidates below the selected one are also valid and deliberately disagree.
        // Earlier candidates are present, but either empty or nonnumeric placeholders.
        for (unparseable in listOf(false, true)) {
            for (selected in 0..3) {
                val candidates = (0..3).map { index ->
                    if (index >= selected) usage((index + 1) * 100, (index + 1) * 10)
                    else if (unparseable) JSONObject().put("input_tokens", "unknown")
                    else JSONObject()
                }
                val response = copyFields(candidates[2], completedResponse())
                    .put("usage", candidates[0])
                val receipt = copyFields(candidates[3], JSONObject())
                    .put("response", response).put("usage", candidates[1])
                withSseServer(event("response.completed", receipt)) { baseUrl ->
                    val ledger = Ledger()
                    val events = mutableListOf<ProviderEvent>()
                    ledger.wrap(OpenAiResponsesProvider).complete(
                        request(baseUrl, OpenAiEndpointMode.RESPONSES), AgentRunController(), events::add,
                    )
                    val reported = events.filterIsInstance<ProviderEvent.Usage>().single().usage
                    assertEquals((selected + 1) * 100, reported.inputTokens)
                    assertEquals((selected + 1) * 10, reported.outputTokens)
                    assertEquals(1, ledger.records.size)
                    ledger.assertTotals((selected + 1) * 100L, (selected + 1) * 10L)
                }
            }
        }
    }

    @Test
    fun responsesExplicitZeroIsValidRatherThanAFallbackPlaceholder() {
        val receipt = JSONObject().put("response", completedResponse().put("usage", usage(0, 0)))
            .put("usage", usage(900, 90))
        withSseServer(event("response.completed", receipt)) { baseUrl ->
            val ledger = Ledger()
            val events = mutableListOf<ProviderEvent>()
            ledger.wrap(OpenAiResponsesProvider).complete(
                request(baseUrl, OpenAiEndpointMode.RESPONSES), AgentRunController(), events::add,
            )
            val reported = events.filterIsInstance<ProviderEvent.Usage>().single().usage
            assertEquals(0, reported.inputTokens)
            assertEquals(0, reported.outputTokens)
            assertEquals(1, ledger.records.size)
            ledger.assertTotals(0, 0)
        }
    }

    @Test
    fun responsesCompletedReceiptAndTerminalReconciliationReportAndRecordOnce() {
        val sameUsage = usage(100, 20, 60, 5)
        val body = event("response.in_progress", JSONObject().put("usage", sameUsage)) +
            event("response.completed", JSONObject().put("usage", sameUsage)
                .put("response", completedResponse().put("usage", sameUsage)))
        withSseServer(body) { baseUrl ->
            val ledger = Ledger()
            val events = mutableListOf<ProviderEvent>()
            ledger.wrap(OpenAiResponsesProvider).complete(
                request(baseUrl, OpenAiEndpointMode.RESPONSES), AgentRunController(), events::add,
            )
            assertEquals(1, events.filterIsInstance<ProviderEvent.Usage>().size)
            assertEquals(1, events.filterIsInstance<ProviderEvent.Completed>().size)
            assertEquals(1, ledger.records.size)
            ledger.assertTotals(100, 20, 60, 5)
        }
    }

    @Test
    fun bothProvidersReplaceCumulativeLedgerSnapshotsAndRetainMissingBillingFields() {
        for (mode in listOf(OpenAiEndpointMode.CHAT_COMPLETIONS, OpenAiEndpointMode.RESPONSES)) {
            val first = usage(100, 10, 50, 5)
            val second = usage(120, 30, 60, 7)
            val partial = JSONObject().put("output_tokens", 40)
            val body = if (mode == OpenAiEndpointMode.CHAT_COMPLETIONS) {
                listOf(first, second, second, partial).joinToString("") {
                    frame(JSONObject().put("usage", it))
                } + chatCompleted()
            } else {
                listOf(first, second, second).joinToString("") {
                    event("response.in_progress", JSONObject().put("response", JSONObject().put("usage", it)))
                } + event("response.completed", JSONObject()
                    .put("response", completedResponse().put("usage", partial)))
            }
            withSseServer(body) { baseUrl ->
                val ledger = Ledger()
                val events = mutableListOf<ProviderEvent>()
                val provider = if (mode == OpenAiEndpointMode.CHAT_COMPLETIONS) {
                    OpenAiChatCompletionsProvider
                } else OpenAiResponsesProvider
                ledger.wrap(provider).complete(request(baseUrl, mode), AgentRunController(), events::add)
                val expected = listOf(
                    AgentTokenUsage(contextTokens = 110, inputTokens = 100, outputTokens = 10,
                        cachedTokens = 50, cacheCreationTokens = 5),
                    AgentTokenUsage(contextTokens = 150, inputTokens = 120, outputTokens = 30,
                        cachedTokens = 60, cacheCreationTokens = 7),
                )
                val usages = events.filterIsInstance<ProviderEvent.Usage>().map { it.usage }
                assertEquals(expected +
                    (if (mode == OpenAiEndpointMode.CHAT_COMPLETIONS) listOf(expected.last()) else emptyList()) +
                    AgentTokenUsage(outputTokens = 40), usages)
                // Three changed snapshots, one request UUID and one final ledger entry, not 100 + 120.
                assertEquals(3, ledger.records.size)
                assertEquals(1, ledger.records.map { it.requestId }.distinct().size)
                ledger.assertTotals(120, 40, 60, 7)
            }
        }
    }

    @Test
    fun responsesSameFrameErrorStillReportsFallbackUsageAndKeepsLedger() {
        val receipt = JSONObject().put("response", JSONObject().put("usage", JSONObject()))
            .put("usage", usage(100, 20, 60, 5))
            .put("error", JSONObject().put("code", "billing_error").put("message", "original responses failure"))
        withSseServer(event("error", receipt)) { baseUrl ->
            val ledger = Ledger()
            val order = mutableListOf<String>()
            val failure = assertThrows(AgentModelFailure::class.java) {
                ledger.wrap(OpenAiResponsesProvider).complete(
                    request(baseUrl, OpenAiEndpointMode.RESPONSES), AgentRunController(),
                ) { event ->
                    if (event is ProviderEvent.Usage) order += "usage"
                    assertFalse(event is ProviderEvent.Completed)
                }
            }
            order += "error"
            assertEquals(listOf("usage", "error"), order)
            assertEquals("PROVIDER_STREAM_ERROR", failure.code)
            assertFalse(failure.retryable)
            assertEquals("模型接口 SSE 返回错误：original responses failure", failure.message)
            assertEquals(1, ledger.records.size)
            ledger.assertTotals(100, 20, 60, 5)
        }
    }

    @Test
    fun streamedPreambleAndHostedToolDoNotHideTerminalThinkingAndAnswer() {
        val hosted = JSONObject().put("id", "ws_1").put("type", "web_search_call").put("status", "completed")
        val body = textEvent("response.output_text.delta", "intro", 0, "delta", "我先查一下。") +
            textEvent("response.output_text.done", "intro", 0, "text", "我先查一下。") +
            event("response.output_item.added", JSONObject().put("output_index", 1).put("item", hosted)) +
            event("response.output_item.done", JSONObject().put("output_index", 1).put("item", hosted)) +
            completed(JSONArray().put(messageItem("intro", "我先查一下。")).put(hosted)
                .put(reasoningItem("after_search", "整理搜索结果"))
                .put(messageItem("answer", "最终答案。")))
        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                request(baseUrl, OpenAiEndpointMode.RESPONSES, hostedTools = true),
                AgentRunController(), events::add,
            )
            assertEquals("我先查一下。最终答案。", result.assistantMessage.getString("content"))
            assertEquals("整理搜索结果", result.assistantMessage.getString("reasoning_content"))
            assertEquals(listOf(
                "start:TEXT:0", "delta:TEXT:0:我先查一下。", "end:TEXT:0",
                "hosted-start:ws_1", "hosted-end:ws_1",
                "start:THINKING:1", "delta:THINKING:1:整理搜索结果", "end:THINKING:1",
                "start:TEXT:2", "delta:TEXT:2:最终答案。", "end:TEXT:2",
            ), events.mapNotNull(::timelineLabel))
        }
    }

    @Test
    fun terminalOnlyTextThinkingAndToolCallKeepTheirVisibleOrder() {
        val call = JSONObject().put("id", "fc_1").put("type", "function_call")
            .put("call_id", "call_1").put("name", "terminal").put("arguments", "{\"command\":\"pwd\"}")
        val body = completed(JSONArray().put(messageItem("intro", "先说明。"))
            .put(reasoningItem("analysis", "选择工具"))
            .put(call))
        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                request(baseUrl, OpenAiEndpointMode.RESPONSES), AgentRunController(), events::add,
            )
            val starts = events.filterIsInstance<ProviderEvent.BlockStart>()
            assertEquals(listOf(AssistantBlockKind.TEXT, AssistantBlockKind.THINKING, AssistantBlockKind.TOOL_CALL),
                starts.map { it.kind })
            assertEquals(listOf(0, 1, 2), starts.map { it.index })
            val ends = events.filterIsInstance<ProviderEvent.BlockEnd>()
            assertEquals(listOf("先说明。", "选择工具", "{\"command\":\"pwd\"}"), ends.map { it.content })
            assertEquals("call_1", ends.last().blockId)
            assertEquals("terminal", ends.last().name)
            assertEquals("选择工具", result.assistantMessage.getString("reasoning_content"))
            assertEquals("tool_calls", result.assistantMessage.getString("finish_reason"))
            assertEquals("call_1", result.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        }
    }

    @Test
    fun lateThinkingBeforeRewrittenSoleTextStillDoesNotAppendACard() {
        val body = textEvent("response.output_text.delta", "stream_id", 9, "delta", "答案") +
            textEvent("response.output_text.done", "stream_id", 9, "text", "答案") +
            completed(JSONArray().put(reasoningItem("late", "此前的分析"))
                .put(messageItem("terminal_id", "答案完整。")))
        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                request(baseUrl, OpenAiEndpointMode.RESPONSES), AgentRunController(), events::add,
            )
            assertEquals(listOf(AssistantBlockKind.TEXT), events.filterIsInstance<ProviderEvent.BlockStart>().map { it.kind })
            assertFalse(events.filterIsInstance<ProviderEvent.BlockDelta>().any { it.kind == AssistantBlockKind.THINKING })
            assertEquals("答案完整。", events.filterIsInstance<ProviderEvent.BlockEnd>().last().content)
            assertEquals("此前的分析", result.assistantMessage.getString("reasoning_content"))
            assertNotNull(result.assistantMessage.optJSONObject(ResponsesReasoningState.KEY))
        }
    }

    @Test
    fun lateThinkingUsesAuthoritativePartPositionsWithMultipleContentIndexes() {
        val body = textEvent("response.output_text.delta", "answer", 2, "delta", "已交付答案", contentIndex = 1) +
            textEvent("response.output_text.done", "answer", 2, "text", "已交付答案", contentIndex = 1) +
            completed(JSONArray().put(messageItem("intro", "说明一", "说明二"))
                .put(reasoningItem("late", "此前的分析"))
                .put(messageItem("answer", "答案前缀", "已交付答案")))
        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                request(baseUrl, OpenAiEndpointMode.RESPONSES), AgentRunController(), events::add,
            )
            assertFalse(events.filterIsInstance<ProviderEvent.BlockStart>().any { it.kind == AssistantBlockKind.THINKING })
            assertFalse(events.filterIsInstance<ProviderEvent.BlockDelta>().any { it.kind == AssistantBlockKind.THINKING })
            assertEquals(4, events.filterIsInstance<ProviderEvent.BlockStart>().count { it.kind == AssistantBlockKind.TEXT })
            assertEquals("说明一说明二答案前缀已交付答案", result.assistantMessage.getString("content"))
            assertEquals("此前的分析", result.assistantMessage.getString("reasoning_content"))
        }
    }

    @Test
    fun matchingStreamedThinkingStillReconcilesInPlaceBeforeDeliveredText() {
        val body = textEvent("response.reasoning_summary_text.delta", "reason", 0, "delta", "draft") +
            textEvent("response.reasoning_summary_text.done", "reason", 0, "text", "draft") +
            textEvent("response.output_text.delta", "answer", 1, "delta", "答案") +
            textEvent("response.output_text.done", "answer", 1, "text", "答案") +
            completed(JSONArray().put(reasoningItem("reason", "authoritative reasoning"))
                .put(messageItem("answer", "答案")))
        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            OpenAiResponsesProvider.complete(
                request(baseUrl, OpenAiEndpointMode.RESPONSES), AgentRunController(), events::add,
            )
            val start = events.filterIsInstance<ProviderEvent.BlockStart>().single { it.kind == AssistantBlockKind.THINKING }
            val end = events.filterIsInstance<ProviderEvent.BlockEnd>().last { it.kind == AssistantBlockKind.THINKING }
            assertEquals(start.index, end.index)
            assertEquals("authoritative reasoning", end.content)
            assertTrue(end.replaceContent)
        }
    }

    private class Ledger {
        val records = mutableListOf<ModelUsageDelta>()
        private var raw = ""

        fun wrap(provider: AgentProviderClient): AgentProviderClient = UsageRecordingProvider(provider) {
            records += it
            raw = applyModelUsageDelta(raw, it)
        }

        fun assertTotals(input: Long, output: Long, cached: Long = 0, created: Long = 0) {
            val snapshot = decodeModelUsageSnapshot(raw)
            assertEquals(input, snapshot.totalInputTokens)
            assertEquals(output, snapshot.totalOutputTokens)
            assertEquals(cached, snapshot.totalCachedTokens)
            assertEquals(created, snapshot.totalCacheCreationTokens)
            val entry = snapshot.providers.single().models.single().events.single()
            assertEquals(records.last().requestId, entry.requestId)
            assertEquals("regression-conversation", entry.conversationId)
        }
    }

    private fun request(baseUrl: String, mode: String, hostedTools: Boolean = false) = ProviderRequest(
        config = AgentModelClient.ModelConfig(
            providerId = "regression-provider",
            providerName = "Regression provider",
            providerSourceType = "custom",
            baseUrl = baseUrl,
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            openAiEndpointMode = mode,
            hostedWebSearchEnabled = hostedTools,
        ),
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
        tools = JSONArray(),
        sessionId = "regression-conversation",
        usageConversationId = "regression-conversation",
    )

    private fun usage(input: Int, output: Int, cached: Int = 0, created: Int = 0): JSONObject = JSONObject()
        .put("input_tokens", input).put("output_tokens", output).put("total_tokens", input + output)
        .put("input_tokens_details", JSONObject().put("cached_tokens", cached).put("cache_creation_tokens", created))

    private fun frame(fields: JSONObject): String = "data: $fields\n\n"

    private fun event(type: String, fields: JSONObject): String =
        "event: $type\ndata: ${JSONObject(fields.toString()).put("type", type)}\n\n"

    private fun chatCompleted(): String = frame(JSONObject().put("choices", JSONArray().put(
        JSONObject().put("delta", JSONObject().put("content", "ok")).put("finish_reason", "stop"),
    ))) + "data: [DONE]\n\n"

    private fun completedResponse(output: JSONArray = JSONArray().put(messageItem("answer", "ok"))): JSONObject =
        JSONObject().put("status", "completed").put("output", output)

    private fun completed(output: JSONArray): String =
        event("response.completed", JSONObject().put("response", completedResponse(output)))

    private fun textEvent(
        type: String,
        itemId: String,
        outputIndex: Int,
        valueKey: String,
        value: String,
        contentIndex: Int = 0,
    ): String = event(type, JSONObject().put("item_id", itemId).put("output_index", outputIndex)
        .put("content_index", contentIndex).put("summary_index", contentIndex).put(valueKey, value))

    private fun messageItem(id: String, vararg text: String): JSONObject = JSONObject()
        .put("id", id).put("type", "message")
        .put("content", JSONArray().also { content ->
            text.forEach { content.put(JSONObject().put("type", "output_text").put("text", it)) }
        })

    private fun reasoningItem(id: String, text: String): JSONObject = JSONObject()
        .put("id", id).put("type", "reasoning")
        .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", text)))

    private fun copyFields(source: JSONObject, target: JSONObject): JSONObject = target.also {
        source.keys().forEach { key -> it.put(key, source.get(key)) }
    }

    private fun timelineLabel(event: ProviderEvent): String? = when (event) {
        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
        is ProviderEvent.HostedToolStarted -> "hosted-start:${event.id}"
        is ProviderEvent.HostedToolFinished -> "hosted-end:${event.id}"
        else -> null
    }

    private fun withSseServer(body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        for (path in listOf("/chat/completions", "/responses")) {
            server.createContext(path) { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
