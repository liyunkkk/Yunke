package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure loop tests: no network, terminal backend, Android lifecycle or command execution.
 *
 * These tests drive the real [AgentLoop] with a fake provider and verify that
 * [AgentToolCallDiagnostics] is wired through the private `toolDiagnostics` constructor parameter
 * while the executor call, the paired history and the tool result are all preserved.
 *
 * The diagnostic payload is inspected only through stable stage/environment substrings, never by
 * parsing its JSON fields, so the agreed diagnostics mapping can be refined without rewriting the
 * tests. Stage markers come from the agreed API mapping.
 */
class AgentToolDiagnosticsLoopIntegrationTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(45)

    @Test
    fun enabledDiagnosticsRecordStagesAndPreserveExecutorAndHistory() {
        val logs = mutableListOf<String>()
        val call = Call(CALL_ID, TERMINAL, terminalArguments())
        val run = runBatches(
            batches = listOf(listOf(call)),
            diagnostics = AgentToolCallDiagnostics(enabled = { true }, sink = { logs += it }),
        )

        assertCompleted(run, requests = 2, results = 1)
        // Diagnostics never replace the executor: same name and the exact argument string arrive.
        assertEquals(listOf(call), run.executed)
        // The tool response stored in history is exactly what the executor returned.
        assertEquals(listOf(EXECUTOR_RESULT), run.toolResults)

        assertTrue("enabled diagnostics must emit lines", logs.isNotEmpty())
        assertTrue(
            "every emitted line must carry the ToolCallDiag prefix",
            logs.all { it.startsWith("ToolCallDiag ") },
        )
        val text = logs.joinToString("\n")
        // Requested environment (arguments) and actual environment (result) both surface.
        assertTrue("requested environment missing:\n$text", text.contains(REQUESTED_ENVIRONMENT))
        assertTrue("actual environment missing:\n$text", text.contains("debian"))
        val stages = logs.map { JSONObject(it.removePrefix("ToolCallDiag ")).getString("stage") }.toSet()
        for (stage in STAGES) {
            assertTrue("diagnostic stage '$stage' missing:\n$text", stage in stages)
        }
        assertNoRawInputsOrSecrets(text, call)
    }

    @Test
    fun invalidArgumentsAreDiagnosedWithValidationButNoDispatchOrExecution() {
        val logs = mutableListOf<String>()
        val call = Call("call-invalid-diag", RUN_COMMAND, "{}")
        val run = runBatches(
            batches = listOf(listOf(call)),
            diagnostics = AgentToolCallDiagnostics(enabled = { true }, sink = { logs += it }),
        )

        assertCompleted(run, requests = 2, results = 1)
        assertTrue("a rejected call must never reach the executor", run.executed.isEmpty())
        val text = logs.joinToString("\n")
        assertTrue("validation stage missing:\n$text", text.contains("validation"))
        assertFalse("a rejected call must not be dispatched:\n$text", text.contains("dispatch"))
        assertFalse("a rejected call must not report an execution result:\n$text",
            text.contains("debian"))
        assertFalse("raw call id leaked:\n$text", text.contains(call.id))
    }

    @Test
    fun disabledDiagnosticsEmitNoLinesAndDoNotChangeExecution() {
        val logs = mutableListOf<String>()
        val call = Call(CALL_ID, TERMINAL, terminalArguments())
        val run = runBatches(
            batches = listOf(listOf(call)),
            diagnostics = AgentToolCallDiagnostics(enabled = { false }, sink = { logs += it }),
        )

        assertCompleted(run, requests = 2, results = 1)
        assertEquals(listOf(call), run.executed)
        assertEquals(listOf(EXECUTOR_RESULT), run.toolResults)
        assertTrue("disabled diagnostics must stay silent", logs.isEmpty())
    }

    @Test
    fun defaultDiagnosticsRemainDisabledAndDoNotChangeExecution() {
        val call = Call(CALL_ID, TERMINAL, terminalArguments())
        val run = runBatches(batches = listOf(listOf(call)))

        assertCompleted(run, requests = 2, results = 1)
        assertEquals(listOf(call), run.executed)
        assertEquals(listOf(EXECUTOR_RESULT), run.toolResults)
    }

    @Test
    fun throwingSinkNeverBreaksExecutionOrChangesHistory() {
        val call = Call(CALL_ID, TERMINAL, terminalArguments())
        val run = runBatches(
            batches = listOf(listOf(call)),
            diagnostics = AgentToolCallDiagnostics(
                enabled = { true },
                sink = { throw IllegalStateException("diagnostics sink failure") },
            ),
        )

        assertCompleted(run, requests = 2, results = 1)
        assertEquals(listOf(call), run.executed)
        assertEquals(listOf(EXECUTOR_RESULT), run.toolResults)
    }

    private data class Call(val id: String, val name: String, val arguments: String)

    private data class Run(
        val requests: Int,
        val history: JSONArray,
        val executed: List<Call>,
        val failure: AgentModelFailure?,
        val completion: AgentLoop.Result?,
    ) {
        /** Raw tool result contents in the order they were paired into history. */
        val toolResults: List<String>
            get() = (0 until history.length())
                .map { history.getJSONObject(it) }
                .filter { it.optString("role") == "tool" }
                .map { it.getString("content") }
    }

    private fun runBatches(
        batches: List<List<Call>>,
        diagnostics: AgentToolCallDiagnostics = AgentToolCallDiagnostics(),
    ): Run {
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("完成测试任务"))
        val executed = mutableListOf<Call>()
        var requests = 0
        val provider = object : AgentProviderClient {
            override val id = "tool-diagnostics-loop-test"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                // At every provider boundary all previous assistant calls must be paired.
                assertPaired(history)
                requests++
                check(requests <= batches.size + 1) { "Unexpected extra provider request" }
                if (requests == batches.size + 1) {
                    return ProviderResponse(JSONObject().put("role", "assistant")
                        .put("content", "测试完成").put("finish_reason", "stop"))
                }
                return ProviderResponse(JSONObject()
                    .put("role", "assistant")
                    .put("content", "")
                    .put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().apply {
                        batches[requests - 1].forEach { call ->
                            put(JSONObject().put("id", call.id).put("type", "function")
                                .put("function", JSONObject().put("name", call.name)
                                    .put("arguments", call.arguments)))
                        }
                    }))
            }
        }
        var failure: AgentModelFailure? = null
        var completion: AgentLoop.Result? = null
        try {
            completion = AgentLoop(
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test",
                    systemPrompt = "", contextWindow = 1_000_000,
                ),
                messages = history,
                tools = tools(),
                provider = provider,
                toolExecutor = AgentModelClient.ToolExecutor { call ->
                    executed += Call(call.id, call.name, call.argumentsJson)
                    AgentModelClient.ToolResult(EXECUTOR_RESULT)
                },
                runController = AgentRunController(),
                traceFormatter = AgentTraceFormatter(),
                onEvent = {},
                toolDiagnostics = diagnostics,
            ).run()
        } catch (caught: AgentModelFailure) {
            failure = caught
        }
        assertPaired(history)
        return Run(requests, history, executed, failure, completion)
    }

    private fun assertCompleted(run: Run, requests: Int, results: Int) {
        assertNull(run.failure)
        assertEquals("测试完成", run.completion?.content)
        assertEquals(requests, run.requests)
        assertEquals(results, run.toolResults.size)
    }

    private fun assertPaired(history: JSONArray) {
        var pending = emptyList<String>()
        var paired = 0
        for (index in 0 until history.length()) {
            val message = history.getJSONObject(index)
            when (message.optString("role")) {
                "assistant" -> {
                    assertEquals("previous batch must be fully paired", pending.size, paired)
                    val calls = message.optJSONArray("tool_calls")
                    pending = if (calls == null) emptyList() else (0 until calls.length())
                        .map { calls.getJSONObject(it).getString("id") }
                    paired = 0
                }
                "tool" -> {
                    assertTrue("no duplicate or unpaired result", paired < pending.size)
                    assertEquals("result order must match assistant calls", pending[paired],
                        message.getString("tool_call_id"))
                    paired++
                }
            }
        }
        assertEquals("every call in the final batch must have a result", pending.size, paired)
    }

    /**
     * Raw identifiers and payloads are forbidden. Fixed terminal/run_command labels are
     * intentional allowlisted metadata; arbitrary tool names are fingerprinted.
     */
    private fun assertNoRawInputsOrSecrets(text: String, call: Call) {
        assertFalse("raw call id leaked:\n$text", text.contains(call.id))
        assertFalse("raw cwd leaked:\n$text", text.contains(CWD_SECRET))
        assertFalse("raw command leaked:\n$text", text.contains(COMMAND_SECRET))
        assertFalse("raw stdout leaked:\n$text", text.contains(STDOUT_SECRET))
        assertFalse("raw stderr leaked:\n$text", text.contains(STDERR_SECRET))
        assertFalse("raw argument JSON leaked:\n$text", text.contains(terminalArguments()))
    }

    private fun terminalArguments(): String = JSONObject()
        .put("action", "open_and_exec")
        .put("environment", REQUESTED_ENVIRONMENT)
        .put("command", COMMAND_SECRET)
        .put("cwd", CWD_SECRET)
        .toString()

    private fun tools() = JSONArray()
        .put(tool(TERMINAL, "action", "environment", "command", "cwd"))
        .put(tool(RUN_COMMAND, "command"))

    private fun tool(name: String, vararg required: String): JSONObject {
        val properties = JSONObject()
        required.forEach { field ->
            properties.put(field, JSONObject().put("type", "string").put("minLength", 1))
        }
        val requiredArray = JSONArray()
        required.forEach { requiredArray.put(it) }
        return AgentToolSchema.function(
            name = name,
            description = "test $name",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", requiredArray),
        )
    }

    private companion object {
        const val TERMINAL = "terminal"
        const val RUN_COMMAND = "run_command"
        const val CALL_ID = "call-diag-1"
        const val REQUESTED_ENVIRONMENT = "linux"
        const val COMMAND_SECRET = "secret-command-marker"
        const val CWD_SECRET = "/secret-cwd"
        const val STDOUT_SECRET = "secret-output"
        const val STDERR_SECRET = "secret-error"

        /** Stage markers from the agreed diagnostics API mapping. */
        val STAGES = listOf("provider_parsed", "parsed", "validation", "dispatch", "result")

        /** Fake terminal result: the requested environment was linux, the actual one is debian. */
        val EXECUTOR_RESULT: String = JSONObject()
            .put("ok", true)
            .put("tool", TERMINAL)
            .put("environment", "debian")
            .put("exit_code", 0)
            .put("stdout", STDOUT_SECRET)
            .put("stderr", STDERR_SECRET)
            .toString()
    }
}
