package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/** No sleeps or real network: the same clock advances waits AND in-flight request work. */
class AgentErrorReconnectTest {
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
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                now = task.at
                task.action()
            }
            now = end
        }
    }
    private fun config(policy: ErrorReconnectPolicy) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "never-in-events", model = "test", systemPrompt = "",
        errorReconnectPolicy = policy.persistedValue)
    private fun ok(text: String = "done") = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))
    private fun provider(action: (ProviderRequest, AgentRunController, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
                action(request, runController, onEvent)
        }
    private fun run(clock: Clock, policy: ErrorReconnectPolicy, provider: AgentProviderClient,
        events: MutableList<AgentEvent> = mutableListOf(), controller: AgentRunController = AgentRunController(),
        providerEvents: MutableList<ProviderEvent> = mutableListOf(), hosted: Boolean = false,
        wait: ((AgentRunController, Long) -> Unit)? = null,
        onCancelledResponse: (ProviderResponse) -> Unit = {},
    ) = AgentModelRetry(
        waitBeforeRetry = { control, ms -> (wait ?: { _: AgentRunController, delay: Long -> clock.advance(delay) })(control, ms) },
        timing = clock,
    ).complete(
        1, ProviderRequest(config(policy).copy(hostedWebSearchEnabled = hosted), JSONArray(), JSONArray()), provider,
        controller, events::add, { _, event -> providerEvents += event }, {}, onCancelledResponse)

    @Test fun noneDoesNotHaveLegacyThreeNetworkRetries() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.NONE, provider { _, _, _ -> calls++; throw IOException() }, events,
                wait = { _, _ -> fail("none cannot wait") })
        }
        assertEquals(1, calls)
        assertEquals(listOf("failed"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
        assertTrue(events.none { it is AgentEvent.ModelRetryScheduled })
    }

    @Test fun finitePoliciesExpireFromFirstErrorNotEachRetryAndTickEverySecond() {
        for (policy in listOf(ErrorReconnectPolicy.WINDOW_30S, ErrorReconnectPolicy.WINDOW_1M, ErrorReconnectPolicy.WINDOW_5M)) {
            val clock = Clock()
            val events = mutableListOf<AgentEvent>()
            var calls = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(clock, policy, provider { _, _, _ ->
                    calls++
                    if (calls == 1) clock.advance(7_000) // Not part of reconnect budget before first error.
                    else clock.advance(1_700)
                    throw AgentModelFailure.http(401, "")
                }, events)
            }
            assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
            val changed = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
            assertEquals(1, changed.map { it.reconnectId }.distinct().size)
            assertEquals("failed", changed.last().status)
            assertTrue(changed.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
            assertEquals((0 until policy.windowMillis!! step 1_000).toList(), changed.filter { it.status == "running" }.map { it.elapsedMs })
            assertTrue(clock.now < 7_000 + policy.windowMillis!! + 1_700)
            assertTrue(calls > 4) // No fixed network retry counter.
        }
    }

    @Test fun deadlineCancelsInflightTransportButNotTheRunAndRejectsLateSuccess() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val controller = AgentRunController()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        var cancelled = false
        var late: ((ProviderEvent) -> Unit)? = null
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, control, emit ->
                if (calls++ == 0) throw IOException()
                val binding = control.register(interruptible = true) { cancelled = true }
                try {
                    late = emit
                    clock.advance(29_000)
                    assertTrue(cancelled)
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "late answer"))
                    ok("late answer") // A provider ignoring cancellation cannot win.
                } finally { binding.close() }
            }, events, controller, delivered)
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        assertFalse(controller.isCancelled)
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertTrue(delivered.isEmpty())
        assertEquals("failed", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
    }

    @Test fun continuousRunsPastFiveMinutesAndSucceedsOnlyAfterCompleteReturn() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val response = run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, emit ->
            if (calls++ == 0) throw AgentModelFailure.http(400, "")
            clock.advance(305_000)
            emit(ProviderEvent.Completed("stop"))
            assertTrue(events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().none { it.status == "succeeded" })
            ok()
        }, events)
        assertEquals("done", response.response.assistantMessage.getString("content"))
        assertEquals("succeeded", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        assertEquals(306_000L, clock.now)
    }

    @Test fun stopWhileWaitingCancelsImmediatelyAndDoesNotStartAnotherRequest() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> calls++; throw IOException() },
                events, controller, wait = { control, _ -> control.cancel() })
        }
        assertEquals(1, calls)
        assertEquals(listOf("running", "stopped"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
    }

    @Test fun stopInflightClosesTransportAndDropsLateCallbacks() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        var cancelled = false
        var late: ((ProviderEvent) -> Unit)? = null
        assertThrows(AgentRunCancelledException::class.java) {
            run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, control, emit ->
                if (calls++ == 0) throw IOException()
                val binding = control.register(interruptible = true) { cancelled = true }
                try {
                    late = emit
                    control.cancel()
                    assertTrue(cancelled)
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "late text"))
                    ok()
                } finally { binding.close() }
            }, events, providerEvents = delivered)
        }
        assertTrue(cancelled)
        assertEquals(2, calls)
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertTrue(delivered.isEmpty())
        val changed = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals("stopped", changed.last().status)
        assertEquals(1, changed.count { it.status == "stopped" })
        assertTrue(changed.none { it.status == "succeeded" })
    }

    @Test fun stoppedToolResponseIsRecordedThenCancelledWithoutReplayingOrPublishing() {
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        val controller = AgentRunController()
        var calls = 0
        var cancelled = false
        var late: ((ProviderEvent) -> Unit)? = null
        var retained: ProviderResponse? = null
        assertThrows(AgentRunCancelledException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, control, emit ->
                if (calls++ == 0) throw IOException()
                val binding = control.register(interruptible = true) { cancelled = true }
                try {
                    late = emit
                    control.cancel()
                    ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                            .put("id", "stopped-child-call").put("type", "function").put("function", JSONObject()
                                .put("name", "delegate_task").put("arguments", "{}")))))
                } finally { binding.close() }
            }, events, controller, providerEvents = delivered, onCancelledResponse = { retained = it })
        }
        assertTrue(controller.isCancelled)
        assertTrue(cancelled)
        assertEquals(2, calls)
        assertEquals("stopped-child-call", AgentConversationCodec.parseToolCalls(requireNotNull(retained).assistantMessage).single().id)
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertTrue(delivered.isEmpty())
        assertEquals("stopped", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        assertTrue(events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().none { it.status == "succeeded" })
    }

    @Test fun stopBeforeFirstResponseReturnDoesNotRequireReconnectToCancel() {
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        var late: ((ProviderEvent) -> Unit)? = null
        assertThrows(AgentRunCancelledException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, control, emit ->
                calls++
                late = emit
                control.cancel()
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "late text"))
                ok()
            }, events, providerEvents = delivered, wait = { _, _ -> fail("must not retry") })
        }
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertEquals(1, calls)
        assertTrue(delivered.isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().isEmpty())
    }

    @Test fun cancelledResponseObserverFailureIsPreservedAndNeverRetried() {
        val original = IOException("cancelled response persistence failed")
        var calls = 0
        val thrown = assertThrows(IOException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, control, _ ->
                calls++
                control.cancel()
                ok()
            }, wait = { _, _ -> fail("must not retry") }, onCancelledResponse = { throw original })
        }
        assertSame(original, thrown)
        assertEquals(1, calls)
    }

    @Test fun partialTextContinuesWithDraftAndDoesNotRepeatStreamOrFinalBody() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        val result = run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { request, _, emit ->
            if (calls++ == 0) {
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "Hello world"))
                throw IOException()
            }
            assertEquals("Hello world", request.messages.getJSONObject(0).getString("content"))
            assertFalse(request.messages.toString().contains("tool_calls"))
            emit(ProviderEvent.RequestStarted)
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "world"))
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, " again"))
            ok("world again")
        }, events, providerEvents = delivered)
        assertEquals("Hello world again", result.response.assistantMessage.getString("content"))
        assertEquals("Hello world again", delivered.filterIsInstance<ProviderEvent.BlockDelta>().joinToString("") { it.delta })
        assertEquals(listOf("running", "running", "succeeded"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
    }

    @Test fun toolEvidenceRecoversWithToolsDisabledRatherThanFailingImmediately() {
        for (event in listOf<ProviderEvent>(ProviderEvent.HostedToolStarted("h", "remote"),
            ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 0, "{}"))) {
            var calls = 0
            val events = mutableListOf<AgentEvent>()
            val result = run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { request, _, emit ->
                if (calls++ == 0) { emit(event); throw IOException() }
                assertTrue(request.reconnectTextOnly)
                assertEquals(0, request.tools.length())
                assertFalse(request.config.hostedWebSearchEnabled)
                ok()
            }, events)
            assertEquals(2, calls)
            assertEquals("done", result.response.assistantMessage.getString("content"))
            assertEquals("succeeded", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        }
        var calls = 0
        run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { request, _, _ ->
            if (calls++ == 0) throw IOException()
            assertTrue(request.reconnectTextOnly)
            assertFalse(request.config.hostedWebSearchEnabled)
            ok()
        }, hosted = true)
        assertEquals(2, calls)
    }

    @Test fun repeatedToolEvidenceAndUnknownHostedFailuresOnlyFailAtDeadline() {
        for (policy in listOf(ErrorReconnectPolicy.WINDOW_30S, ErrorReconnectPolicy.WINDOW_1M,
            ErrorReconnectPolicy.WINDOW_5M)) {
            val clock = Clock()
            val events = mutableListOf<AgentEvent>()
            var calls = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(clock, policy, provider { request, _, emit ->
                    if (calls++ == 0) emit(ProviderEvent.HostedToolStarted("h", "remote"))
                    else assertTrue(request.reconnectTextOnly)
                    throw IOException("offline")
                }, events, hosted = true)
            }
            assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
            assertTrue(calls > 2)
            assertEquals(policy.windowMillis, clock.now)
            val markers = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
            assertEquals(1, markers.map { it.reconnectId }.distinct().size)
            assertEquals(1, markers.count { it.status == "failed" })
            assertEquals(policy.windowMillis, markers.last().elapsedMs)
        }
    }

    @Test fun textOnlyRecoveryRejectsNewToolCallsAndStillUsesOriginalDeadline() {
        val clock = Clock()
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { request, _, emit ->
                calls++
                if (!request.reconnectTextOnly) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 0, "{}"))
                    throw IOException()
                }
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "unsafe"))))
            })
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        assertEquals(30_000L, clock.now)
        assertTrue(calls > 2)
    }

    @Test fun incompleteArgumentsAndProviderParsingErrorsRetryUntilDeadline() {
        for (error in listOf<Exception>(
            AgentModelFailure("RESPONSES_TOOL_ARGUMENTS_INCOMPLETE", false, "arguments incomplete"),
            org.json.JSONException("gateway returned truncated JSON"))) {
            val clock = Clock()
            val events = mutableListOf<AgentEvent>()
            var calls = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, _, _ -> calls++; throw error }, events)
            }
            assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
            assertEquals(30_000L, clock.now)
            assertTrue(calls > 2)
            assertEquals(1, events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().count { it.status == "failed" })
        }
    }

    @Test fun customPayloadCannotReenableToolsOnTextOnlyReconnect() {
        val request = ProviderRequest(config(ErrorReconnectPolicy.WINDOW_30S), JSONArray(), JSONArray(),
            reconnectTextOnly = true)
        val body = JSONObject().put("model", "test").put("tools", JSONArray())
            .put("tool_choice", "required").put("parallel_tool_calls", true)
            .put("web_search_options", JSONObject()).put("mcp_servers", JSONArray())
            .put("previous_response_id", "remote").put("conversation", "remote")
        request.restrictReconnectPayload(body)
        assertEquals(setOf("model"), body.keySet())
    }

    @Test fun cancellationErrorAndContextMaintenanceAreNotNetworkRetryLoops() {
        val error = AssertionError("provider bug")
        assertSame(error, assertThrows(AssertionError::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> throw error })
        })
        assertThrows(CancellationException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> throw CancellationException() })
        }
        val events = mutableListOf<AgentEvent>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ ->
                throw AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "maintain") }, events)
        }
        assertEquals("CONTEXT_WINDOW_EXCEEDED", failure.code)
        assertTrue(events.none { it is AgentEvent.ErrorReconnectChanged })
    }
}
