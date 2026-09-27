package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentUnexpectedToolNaturalFinishTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(45)

    @Test fun unexpectedToolBatchWithBodyFinishesWithoutLeavingUnpairedCalls() {
        val controller = AgentRunController()
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("task"))
        val events = mutableListOf<AgentEvent>()
        val result = AgentLoop(config(), history, JSONArray(), provider { _, _ ->
            unexpectedToolResponse("done")
        }, AgentModelClient.ToolExecutor { error("unexpected batch must not execute") },
            controller, AgentTraceFormatter(), onEvent = events::add).run()

        assertEquals("done", result.content)
        assertEquals(2, history.length())
        assertEquals("done", history.getJSONObject(1).getString("content"))
        assertFalse(history.getJSONObject(1).has("tool_calls"))
        assertEquals(listOf("get_current_context"), events.filterIsInstance<AgentEvent.AssistantReceived>().single().toolNames)
        assertEquals(1, events.filterIsInstance<AgentEvent.RunFinished>().size)
        assertFalse(controller.queueBoundaryGuidance("too late"))
    }

    @Test fun pendingCompactDrainsBeforeSealAndGuidanceDuringMaintenanceGetsItsOwnRound() {
        val controller = AgentRunController()
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("old".repeat(4000)))
            .put(JSONObject().put("role", "assistant").put("content", "old result"))
            .put(AgentConversationCodec.userTextMessage("task"))
        val events = mutableListOf<AgentEvent>()
        var requests = 0
        var compactions = 0
        var guidanceAcceptedAtFinish: Boolean? = null
        val model = config()
        val result = AgentLoop(model, history, JSONArray(), provider { request, _ ->
            when (++requests) {
                1 -> {
                    assertTrue(controller.requestCompact(keepRecentMessages = 1))
                    unexpectedToolResponse("first answer")
                }
                2 -> {
                    assertTrue(request.messages.toString().contains("guidance during compact"))
                    ProviderResponse(JSONObject().put("role", "assistant").put("content", "continued")
                        .put("finish_reason", "stop"))
                }
                else -> error("unexpected extra request")
            }
        }, AgentModelClient.ToolExecutor { error("unexpected batch must not execute") },
            controller, AgentTraceFormatter(), onEvent = { event ->
                events += event
                if (event is AgentEvent.RunFinished) {
                    // A finished callback must not accept guidance it can no longer consume.
                    guidanceAcceptedAtFinish = controller.queueBoundaryGuidance("too late")
                }
            }, compactPolicy = AgentLoop.CompactPolicy(false, 128_000, 1, model),
            compactHistory = { source, policy ->
                compactions++
                assertTrue(controller.queueBoundaryGuidance("guidance during compact"))
                listOf(AgentModelClient.ConversationMessage("user", "[summary] old facts")) +
                    source.drop(requireNotNull(policy.keepStartOverride))
            }).run()

        assertEquals("continued", result.content)
        assertEquals(2, requests)
        assertEquals(1, compactions)
        assertEquals(false, guidanceAcceptedAtFinish)
        assertEquals(1, events.filterIsInstance<AgentEvent.RunFinished>().size)
        assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompacted>().count { it.applied })
        assertFalse((0 until history.length()).any { history.getJSONObject(it).has("tool_calls") })
        assertTrue((0 until history.length()).any { history.getJSONObject(it).optString("content").contains("guidance during compact") })
    }

    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test",
        model = "test", systemPrompt = "", contextWindow = 128_000)

    private fun unexpectedToolResponse(content: String) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", content).put("finish_reason", "stop")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1").put("type", "function")
            .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}")))))

    private fun provider(block: (ProviderRequest, AgentRunController) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "unexpected-tool-natural-finish"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit): ProviderResponse = block(request, runController)
    }
}
