package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentTraceTest {
    @Test fun runningAndCompletedAreNotConfusedAndEvidenceIsNotInSummary() {
        val formatter = AgentTraceFormatter()
        assertEquals("子代理执行中", formatter.summarizeResult("delegate_task",
            AgentModelClient.ToolResult("{\"ok\":true,\"status\":\"running\"}")))
        val result = formatter.summarizeResult("get_task_result",
            AgentModelClient.ToolResult("{\"ok\":true,\"status\":\"completed\",\"result\":\"private evidence\"}", sensitive = true))
        assertTrue(result.contains("等待主代理审核"))
        assertFalse(result.contains("private evidence"))
    }

    @Test fun delegationIsSensitiveBeforeExecutionAndArgumentsStayOutOfTrace() {
        val formatter = AgentTraceFormatter()
        for (name in SubAgentTools.names) {
            assertTrue(AgentSensitiveToolPolicy.isSensitive(name))
            val call = AgentModelClient.ToolCall("id", name,
                """{"task":"private evidence","context":"private evidence","task_id":"private evidence"}""")
            assertFalse(formatter.summarizeArguments(call).contains("private evidence"))
            assertNull(formatter.displayCommand(call))
        }
    }

    @Test fun cancellationBeforeDelegationExecutionRedactsContext() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test",
            model = "parent", systemPrompt = "")
        val controller = AgentRunController()
        val tools = JSONArray().also { SubAgentTools.appendTo(it, listOf("child")) }
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "review"))
        val provider = object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, false, false, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                                  onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                runController.cancel()
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                        .put("id", "child-call").put("type", "function").put("function", JSONObject()
                            .put("name", "delegate_task").put("arguments",
                                """{"task":"review","context":"private evidence"}""")))))
            }
        }
        val loop = AgentLoop(config, messages, tools, provider,
            { error("Cancelled delegation must not execute") }, controller, AgentTraceFormatter(), {})
        assertThrows(AgentRunCancelledException::class.java) { loop.run() }
        assertTrue(loop.sensitiveToolCallIdsSnapshot().contains("child-call"))
        val transcript = AgentConversationCodec.transcript(messages, 0, loop.sensitiveToolCallIdsSnapshot())
        val persisted = transcript.joinToString { it.content + it.toolCallsJson }
        assertFalse(persisted.contains("private evidence"))
        assertTrue(persisted.contains("redacted"))
    }
}
