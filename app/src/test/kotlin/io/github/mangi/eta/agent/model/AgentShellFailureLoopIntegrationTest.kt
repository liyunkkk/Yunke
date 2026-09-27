package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentShellFailureLoopIntegrationTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(45)

    @Test fun repeatedMissingCommandStopsAfterThreeRequestsWithEveryToolCallPaired() {
        verifyLoop(callsPerBatch = 1, expectedRequests = 3)
    }

    @Test fun failureBudgetReachedInsideBatchStillPairsEveryToolResultBeforeStopping() {
        verifyLoop(callsPerBatch = 3, expectedRequests = 1)
    }

    private fun verifyLoop(callsPerBatch: Int, expectedRequests: Int) {
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("install the missing tool"))
        val controller = AgentRunController()
        var requests = 0
        val provider = object : AgentProviderClient {
            override val id = "shell-failure-loop-test"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests++
                return ProviderResponse(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", "")
                        .put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().apply {
                            repeat(callsPerBatch) { index ->
                                put(JSONObject()
                                    .put("id", "shell-call-$requests-$index")
                                    .put("type", "function")
                                    .put("function", JSONObject()
                                        .put("name", "terminal")
                                        .put("arguments", """
                                            {"action":"open_and_exec","environment":"linux","command":"git status"}
                                        """.trimIndent())))
                            }
                        }),
                )
            }
        }
        val tools = JSONArray().put(AgentToolSchema.function(
            name = "terminal",
            description = "test terminal",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string"))
                    .put("environment", JSONObject().put("type", "string"))
                    .put("command", JSONObject().put("type", "string"))),
        ))

        var failure: AgentModelFailure? = null
        try {
            AgentLoop(
                config = config(),
                messages = history,
                tools = tools,
                provider = provider,
                toolExecutor = AgentModelClient.ToolExecutor {
                    AgentModelClient.ToolResult(JSONObject()
                        .put("ok", false)
                        .put("environment", "debian")
                        .put("stdout", "large output is deliberately omitted")
                        .put("stderr", "git: command not found")
                        .put("exit_code", 127)
                        .toString())
                },
                runController = controller,
                traceFormatter = AgentTraceFormatter(),
                onEvent = {},
            ).run()
        } catch (caught: AgentModelFailure) {
            failure = caught
        }

        assertTrue("the guard must terminate the loop", failure != null)
        assertEquals(expectedRequests, requests)
        val assistantCallIds = mutableSetOf<String>()
        val toolResultIds = mutableSetOf<String>()
        for (index in 0 until history.length()) {
            val message = history.getJSONObject(index)
            when (message.optString("role")) {
                "assistant" -> {
                    val calls = message.optJSONArray("tool_calls") ?: continue
                    for (callIndex in 0 until calls.length()) {
                        assistantCallIds += calls.getJSONObject(callIndex).getString("id")
                    }
                }
                "tool" -> toolResultIds += message.getString("tool_call_id")
            }
        }
        assertEquals(assistantCallIds, toolResultIds)
        assertEquals(3, toolResultIds.size)
        val lastToolResult = history.getJSONObject(history.length() - 1)
        assertEquals("SHELL_COMMAND_NOT_FOUND", JSONObject(lastToolResult.getString("content")).getString("code"))
        assertTrue(JSONObject(lastToolResult.getString("content")).getString("message").contains("debian"))
        assertTrue(failure!!.message!!.contains(AgentShellFailureGuard.STOP_CODE))
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test",
        model = "test",
        systemPrompt = "",
        contextWindow = 128_000,
    )
}
