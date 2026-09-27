package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentRunnerTest {
    @Test fun independentContextReadToolLoopAndFinalResult() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test",
            model = "child", systemPrompt = "PRIVATE ASSISTANT INSTRUCTIONS", hostedWebSearchEnabled = true)
        val tools = JSONArray().put(AgentToolSchema.function("device_status", "read", JSONObject().put("type", "object")))
        tools.put(AgentToolSchema.function("write_file", "write", JSONObject().put("type", "object")))
        SubAgentTools.appendTo(tools, listOf("recursive worker"))
        var rounds = 0
        var executions = 0
        val provider = object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, false, false, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                assertFalse(request.messages.toString().contains("PRIVATE ASSISTANT INSTRUCTIONS"))
                assertFalse(request.config.hostedWebSearchEnabled)
                assertEquals(2, request.tools.length())
                assertEquals("report_task_progress", request.tools.getJSONObject(1).getJSONObject("function").getString("name"))
                if (++rounds == 1) {
                    assertEquals(2, request.messages.length())
                    assertEquals("inspect device", request.messages.getJSONObject(1).getString("content"))
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                            .put("id", "read").put("type", "function").put("function", JSONObject()
                                .put("name", "device_status").put("arguments", "{}")))))
                }
                assertTrue(request.messages.toString().contains("battery evidence"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "reviewed evidence").put("finish_reason", "stop"))
            }
        }
        val result = SubAgentRunner.run(config, "inspect device", tools,
            { executions++; AgentModelClient.ToolResult("battery evidence") }, AgentRunController(), provider, compactPolicy = AgentLoop.CompactPolicy.Disabled)
        assertEquals("reviewed evidence", result)
        assertEquals(2, rounds)
        assertEquals(1, executions)
    }
    @Test(timeout = 5000) fun overLimitWithoutCompressibleHistoryFailsInsteadOfWaitingForTimeout() {
        val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test",
            model = "child", systemPrompt = "", contextWindow = 8000)
        var requests = 0
        val provider = scripted { _, _ ->
            requests++
            throw AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "provider confirmed overflow")
        }
        assertThrows(SubAgentContextLimitException::class.java) {
            SubAgentRunner.run(model, "large".repeat(10000), JSONArray(), { error("No tools") },
                AgentRunController(), provider,
                compactPolicy = AgentLoop.CompactPolicy(true, 8000, 0, model))
        }
        assertEquals(0, requests)
    }

    @Test(timeout = 5000) fun childCompactsAtPressureAndKeepsCurrentToolBatch() {
        val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test",
            model = "child", systemPrompt = "", contextWindow = 40000)
        val tools = JSONArray().put(AgentToolSchema.function("device_status", "read", JSONObject().put("type", "object")))
        var calls = 0
        var compactions = 0
        val toolText = "live tool evidence"
        val provider = scripted { request, emit ->
            if (++calls == 1) {
                emit(ProviderEvent.Usage(io.github.mangi.eta.agent.runtime.AgentTokenUsage(inputTokens = 33000)))
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "read").put("type", "function")
                        .put("function", JSONObject().put("name", "device_status").put("arguments", "{}")))))
            } else {
                assertEquals(1, compactions)
                assertTrue(request.messages.toString().contains("task summary"))
                assertTrue(request.messages.toString().contains(toolText))
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
            }
        }
        val result = SubAgentRunner.run(model, "task background ".repeat(3000), tools,
            { AgentModelClient.ToolResult(toolText) }, AgentRunController(), provider,
            compactPolicy = AgentLoop.CompactPolicy(true, 40000, 0, model),
            compactHistory = { history, policy ->
                compactions++
                assertEquals(40000, policy.contextWindow)
                listOf(AgentModelClient.ConversationMessage("user", "[Conversation summary] task summary")) +
                    history.drop(requireNotNull(policy.keepStartOverride))
            })
        assertEquals("done", result)
        assertEquals(2, calls)
    }

    @Test fun checkpointToolStaysLocalAndDoesNotGrantMutation() {
        val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "private", model = "child", systemPrompt = "")
        val controller = AgentRunController()
        var reported = ""
        controller.setTaskProgressReporter { reported = it; true }
        var round = 0
        val provider = scripted { request, _ ->
            if (++round == 1) {
                assertEquals("report_task_progress", request.tools.getJSONObject(0).getJSONObject("function").getString("name"))
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "checkpoint").put("type", "function")
                        .put("function", JSONObject().put("name", "report_task_progress")
                            .put("arguments", JSONObject().put("summary", "verified step; next inspect").toString())))))
            } else ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
        }
        val result = SubAgentRunner.run(model, "task", JSONArray(), { error("Must not forward report to child executor") },
            controller, provider, compactPolicy = AgentLoop.CompactPolicy.Disabled)
        assertEquals("done", result)
        assertEquals("verified step; next inspect", reported)
    }

    private fun scripted(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, false, false, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request, onEvent)
    }
}
