package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentToolReplayGuardTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(20)

    private fun response(vararg ids: String) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
        .put("tool_calls", JSONArray().also { calls -> ids.forEach { id ->
            calls.put(JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", "read_file").put("arguments", "{}")))
        } }))

    @Test fun historicalCallsWithOrWithoutResultsCannotBeReused() {
        for (hasResult in listOf(false, true)) {
            val history = JSONArray().put(response("old").assistantMessage)
            if (hasResult) history.put(JSONObject().put("role", "tool")
                .put("tool_call_id", "old").put("content", "done"))
            val guard = AgentToolReplayGuard(history)
            assertEquals(AgentToolReplayGuard.CODE, assertThrows(AgentModelFailure::class.java) {
                guard.validate(response("old"))
            }.code)
            guard.validate(response("fresh"))
        }
    }

    @Test fun orphanToolResultIdIsStillProtected() {
        val history = JSONArray().put(JSONObject().put("role", "tool")
            .put("tool_call_id", "orphan").put("content", "committed"))
        assertThrows(AgentModelFailure::class.java) { AgentToolReplayGuard(history).validate(response("orphan")) }
    }

    @Test fun duplicateRawIdsAreRejectedBeforeCodecCanRenameThem() {
        val guard = AgentToolReplayGuard(JSONArray())
        assertEquals(AgentToolReplayGuard.CODE, assertThrows(AgentModelFailure::class.java) {
            guard.validate(response("same", "same"))
        }.code)
        guard.validate(response("one", "two"))
    }

    @Test fun aClaimIsAcceptedOnlyOnceAndRememberedResponsesAreBlocked() {
        val guard = AgentToolReplayGuard(JSONArray())
        assertTrue(guard.claimDispatch("once"))
        assertFalse(guard.claimDispatch("once"))
        guard.remember(response("remembered").assistantMessage)
        assertThrows(AgentModelFailure::class.java) { guard.validate(response("remembered")) }
    }

    @Test fun recoveryConstraintIsIdempotentAndKeepsUnknownRemoteOutcomeExplicit() {
        val messages = JSONArray()
        AgentRecoveryContext.append(messages)
        AgentRecoveryContext.append(messages)
        assertEquals(1, messages.length())
        assertTrue(AgentRecoveryContext.isActive(messages))
        val instruction = messages.getJSONObject(0).getString("content")
        assertTrue(instruction.contains("unknown may already have executed"))
        assertTrue(instruction.contains("equivalent local/browser/HTTP operation"))
        assertTrue(instruction.contains("Never reuse a historical tool_call_id"))
    }

    @Test fun repeatedHistoricalIdsCannotResetFiniteBudgetThroughStreamedText() {
        val history = JSONArray().put(response("old").assistantMessage)
        val guard = AgentToolReplayGuard(history)
        val events = mutableListOf<AgentEvent>()
        var now = 0L
        var requests = 0
        val clock = object : ReconnectTiming {
            override fun nowMs() = now
            override fun schedule(delayMs: Long, action: () -> Unit) = AutoCloseable { }
        }
        val provider = object : AgentProviderClient {
            override val id = "replay-budget-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,
                true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (++requests > 1) onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "still replanning"))
                return response("old")
            }
        }
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentModelRetry(timing = clock, waitBeforeRetry = { _, delay -> now += delay }).complete(1, ProviderRequest(
                AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                    model = "test", systemPrompt = "", errorReconnectPolicy = "window_30s"),
                history, JSONArray()), provider, AgentRunController(), events::add, { _, _ -> }, {},
                validateResponse = guard::validate)
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        assertEquals(30_000L, now)
        assertTrue(requests > 3)
        val markers = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals(1, markers.map { it.reconnectId }.distinct().size)
        assertTrue(markers.none { it.status == "succeeded" })
        assertEquals("failed", markers.last().status)
    }

    @Test fun terminalObserverFailureIsSuppressedWhenRecoveryActivationAlreadyFailed() {
        val primary = IllegalStateException("activation failure")
        val cleanup = IllegalStateException("terminal observer failure")
        val provider = object : AgentProviderClient {
            override val id = "terminal-cleanup-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,
                true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse = throw IOException("disconnect")
        }
        val thrown = assertThrows(IllegalStateException::class.java) {
            AgentModelRetry { _, _ -> fail("activation failure must not retry") }.complete(1,
                ProviderRequest(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid",
                    apiKey = "test", model = "test", systemPrompt = "", errorReconnectPolicy = "continuous"),
                    JSONArray(), JSONArray()), provider, AgentRunController(),
                { event -> if (event is AgentEvent.ErrorReconnectChanged && event.status == "failed") throw cleanup },
                { _, _ -> }, {}, onRecoveryActivated = { throw primary })
        }
        assertSame(primary, thrown)
        assertTrue(thrown.suppressed.any { it === cleanup })
    }

    @Test fun activationIsPublishedBeforeRecoveryContextOverflowEscapesRetry() {
        val durableHistory = JSONArray()
        var activated = 0
        var requests = 0
        val provider = object : AgentProviderClient {
            override val id = "recovery-overflow-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,
                true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (++requests == 1) throw IOException("disconnect")
                assertTrue(request.reconnectLocalToolsOnly)
                assertFalse(request.config.hostedWebSearchEnabled)
                assertTrue(AgentRecoveryContext.isActive(durableHistory))
                throw AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "overflow")
            }
        }
        val failure = assertThrows(AgentModelFailure::class.java) {
            AgentModelRetry { _, _ -> }.complete(1, ProviderRequest(
                AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                    model = "test", systemPrompt = "", hostedWebSearchEnabled = true,
                    errorReconnectPolicy = ErrorReconnectPolicy.CONTINUOUS.persistedValue),
                durableHistory, JSONArray()), provider, AgentRunController(), {}, { _, _ -> }, {},
                onRecoveryActivated = { activated++; AgentRecoveryContext.append(durableHistory) })
        }
        assertEquals("CONTEXT_WINDOW_EXCEEDED", failure.code)
        assertEquals(1, activated)
        assertEquals(2, requests)
        assertTrue(AgentRecoveryContext.isActive(durableHistory))
    }
}
