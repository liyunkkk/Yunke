package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentPostToolReconnectTest {
    private class Clock : ReconnectTiming {
        private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        private val tasks = mutableListOf<Task>()
        override fun nowMs() = now
        override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
            val task = Task(now + delayMs, action)
            tasks += task
            return AutoCloseable { task.cancelled = true }
        }
        fun advance(duration: Long) {
            val end = now + duration
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                now = task.at
                task.action()
            }
            now = end
        }
    }

    private fun catalog() = JSONArray().put(JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", "run_command")
            .put("parameters", JSONObject().put("type", "object")
                .put("required", JSONArray().put("command"))
                .put("properties", JSONObject().put("command", JSONObject().put("type", "string"))))))

    private fun call(id: String, arguments: String) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", "run_command").put("arguments", arguments)))))

    private data class Run(val history: JSONArray, val events: List<AgentEvent>, val requests: Int,
        val executed: List<String>, val failure: AgentModelFailure?, val completion: AgentLoop.Result?)

    private fun run(policy: ErrorReconnectPolicy, clock: Clock, repairAfter: Int? = null,
        duringWait: ((AgentRunController, Long, List<AgentEvent>, Int) -> Unit)? = null,
        terminalFailure: Throwable? = null): Run {
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("finish task"))
        val events = mutableListOf<AgentEvent>()
        val executed = mutableListOf<String>()
        val requests = AtomicInteger()
        val provider = object : AgentProviderClient {
            override val id = "post-tool-reconnect-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,
                true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val index = requests.incrementAndGet()
                assertTrue("bounded by deadline", index <= 16)
                val pending = (0 until history.length()).map { history.getJSONObject(it) }
                val callIds = pending.mapNotNull { it.optJSONArray("tool_calls") }
                    .flatMap { calls -> (0 until calls.length()).map { calls.getJSONObject(it).getString("id") } }
                val resultIds = pending.filter { it.optString("role") == "tool" }
                    .map { it.getString("tool_call_id") }
                assertEquals(callIds, resultIds)
                if (repairAfter != null && index > repairAfter + 1) {
                    return ProviderResponse(JSONObject().put("role", "assistant")
                        .put("content", "done").put("finish_reason", "stop"))
                }
                return call("call-$index", if (repairAfter == index - 1) "{\"command\":\"echo ok\"}" else "{}")
            }
        }
        var failure: AgentModelFailure? = null
        var completion: AgentLoop.Result? = null
        try {
            completion = AgentLoop(
                config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                    model = "test", systemPrompt = "", contextWindow = 1_000_000,
                    errorReconnectPolicy = policy.persistedValue),
                messages = history, tools = catalog(), provider = provider,
                toolExecutor = AgentModelClient.ToolExecutor { tool ->
                    executed += tool.id
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                runController = AgentRunController(), traceFormatter = AgentTraceFormatter(),
                onEvent = { event ->
                    events += event
                    if (event is AgentEvent.ErrorReconnectChanged && event.status == "failed" && terminalFailure != null)
                        throw terminalFailure
                }, reconnectTiming = clock,
                waitForReconnect = { control, delay ->
                    if (duringWait != null) duringWait(control, delay, events, requests.get())
                    else clock.advance(delay)
                },
            ).run()
        } catch (error: AgentModelFailure) {
            failure = error
        }
        return Run(history, events, requests.get(), executed, failure, completion)
    }

    @Test fun terminalObserverFailureDoesNotReplacePrimaryFailureOrLeakPauseBinding() {
        val clock = Clock()
        val primary = IllegalStateException("primary wait failure")
        val cleanup = IllegalStateException("terminal observer failure")
        var control: AgentRunController? = null
        val thrown = assertThrows(IllegalStateException::class.java) {
            run(ErrorReconnectPolicy.WINDOW_30S, clock,
                duringWait = { current, _, _, _ -> control = current; throw primary },
                terminalFailure = cleanup)
        }
        assertSame(primary, thrown)
        assertTrue(thrown.suppressed.any { it === cleanup })
        val observers = AgentRunController::class.java.getDeclaredField("pauseObservers").apply { isAccessible = true }
        assertEquals(0, (observers.get(requireNotNull(control)) as Collection<*>).size)
    }

    @Test fun exhaustedArgumentsKeepTaskAliveAndRunOnlyNewValidatedCall() {
        val clock = Clock()
        val result = run(ErrorReconnectPolicy.WINDOW_30S, clock, repairAfter = 3)
        assertNull(result.failure)
        assertEquals("done", result.completion?.content)
        assertEquals(5, result.requests)
        assertEquals(listOf("call-4"), result.executed)
        assertEquals(2_000L, clock.now)
        assertEquals(listOf("running", "succeeded"), result.events
            .filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status }.distinct())
        assertEquals(listOf("call-1", "call-2", "call-3", "call-4"),
            (0 until result.history.length()).map { result.history.getJSONObject(it) }
                .filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") })
    }

    @Test fun repeatedInvalidArgumentsOnlyStopWhenReconnectWindowExpires() {
        val clock = Clock()
        val result = run(ErrorReconnectPolicy.WINDOW_30S, clock)
        assertEquals("ERROR_RECONNECT_DEADLINE", result.failure?.code)
        assertNull(result.completion)
        assertEquals(30_000L, clock.now)
        assertEquals(9, result.requests)
        assertTrue(result.executed.isEmpty())
        assertEquals("failed", result.events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        assertEquals(listOf(1, 2, 3, 1, 2, 3, 1), result.events
            .filterIsInstance<AgentEvent.ModelRetryScheduled>().map { it.attempt })
    }

    @Test fun postToolPauseStopsTimerAndPreservesPairedResultsUntilResume() {
        val clock = Clock()
        val result = run(ErrorReconnectPolicy.WINDOW_30S, clock, repairAfter = 3,
            duringWait = { control, delay, events, requests ->
                assertEquals(3, requests)
                control.pause()
                assertEquals("stopped", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
                val count = events.size
                clock.advance(120_000)
                assertEquals(count, events.size)
                assertFalse(control.isCancelled)
                control.resume()
                clock.advance(delay)
            })
        assertNull(result.failure)
        assertEquals("done", result.completion?.content)
        assertEquals(5, result.requests)
        assertEquals(listOf("call-4"), result.executed)
        val changes = result.events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals(2, changes.map { it.reconnectId }.distinct().size)
        assertEquals("succeeded", changes.last().status)
        assertEquals(2_000L, changes.last().elapsedMs)
    }

    @Test fun disabledReconnectPreservesBoundedStop() {
        val result = run(ErrorReconnectPolicy.NONE, Clock())
        assertEquals(AgentInvalidToolArgumentsGuard.STOP_CODE, result.failure?.code)
        assertEquals(3, result.requests)
        assertTrue(result.events.none { it is AgentEvent.ErrorReconnectChanged })
    }
}
