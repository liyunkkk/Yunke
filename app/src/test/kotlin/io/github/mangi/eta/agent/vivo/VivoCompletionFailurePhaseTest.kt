package io.github.mangi.eta.agent.vivo

import android.app.Application
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VivoCompletionFailurePhaseTest {
    private lateinit var diagnosticScope: VivoDiagnosticTestScope
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://private.invalid", apiKey = "private-key", model = "private-model")

    @Before fun isolate() { diagnosticScope = VivoDiagnosticTestScope(); ShadowLog.clear() }
    @After fun restore() { if (::diagnosticScope.isInitialized) diagnosticScope.restore(); ShadowLog.clear() }

    private fun provider(block: (ProviderRequest, AgentRunController) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "private-provider"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, false, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            block(request, runController)
    }

    private fun response(text: String, reason: String = "stop") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", reason))

    @Test fun localResponseFailuresHaveDistinctPhasesAndExactlyOneRequestEach() {
        val cases = listOf(
            response("  ") to ModelFeatureCompletion.FailurePhase.BODY_VALIDATION,
            response("null") to ModelFeatureCompletion.FailurePhase.BODY_VALIDATION,
            response("private-partial", "length") to ModelFeatureCompletion.FailurePhase.STOP_REASON_VALIDATION,
            response("private-tool").also {
                it.assistantMessage.put("tool_calls", JSONArray().put(JSONObject().put("id", "private-call")))
            } to ModelFeatureCompletion.FailurePhase.TOOL_CALL_VALIDATION,
        )
        cases.forEach { (response, expected) ->
            diagnosticScope.reset(); ShadowLog.clear()
            var requests = 0
            val parent = AgentRunController()
            val deliveries = mutableListOf<Pair<String, String?>>()
            VivoTextBridgeService.executeModelCall(parent, "private-prompt", { config },
                { selected, messages, onFailurePhase ->
                    ModelFeatureCompletion.complete(selected, messages, parent, "private-session",
                        onFailurePhase = onFailurePhase,
                        providerOverride = provider { request, _ ->
                            requests++
                            assertEquals(0, request.tools.length())
                            assertEquals(2048, request.config.summaryOutputLimit)
                            assertFalse(request.config.hostedWebSearchEnabled)
                            response
                        })
                }, { code, text -> deliveries.add(code to text) })
            assertEquals(1, requests)
            assertEquals(listOf("MODEL_ERROR" to null), deliveries)
            val logs = ShadowLog.getLogsForTag("EtaVivoText")
            assertEquals(listOf("v=1 stage=MODEL_STARTED n=1",
                "v=1 stage=MODEL_FAILED n=2 error=UNKNOWN phase=${expected.name}"), logs.map { it.msg })
            logs.forEach { assertNull(it.throwable); assertFalse(it.msg.contains("private")) }
        }
    }

    @Test fun observerOnlySeesClosedPhaseAndCannotMaskOriginalProviderFailure() {
        val original = IllegalArgumentException("private-request private-key")
        val phases = mutableListOf<ModelFeatureCompletion.FailurePhase>()
        var requests = 0
        var child: AgentRunController? = null
        val caught = assertThrows(IllegalArgumentException::class.java) {
            ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "private-session",
                providerOverride = provider { _, controller -> requests++; child = controller; throw original },
                onFailurePhase = { phases.add(it); throw AssertionError("private-observer-error") })
        }
        assertSame(original, caught)
        assertEquals(1, requests)
        assertEquals(listOf(ModelFeatureCompletion.FailurePhase.PROVIDER_CALL), phases)
        assertTrue(child!!.isCancelled)
        assertTrue(ShadowLog.getLogsForTag("EtaVivoText").isEmpty())
    }

    @Test fun nullObserverPreservesNonVivoSuccessAndExactThrownObject() {
        var requests = 0
        assertEquals("answer", ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "private-session",
            providerOverride = provider { _, _ -> requests++; response(" answer ") }))
        val original = IllegalStateException("private-provider-failure")
        val caught = assertThrows(IllegalStateException::class.java) {
            ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "private-session",
                providerOverride = provider { _, _ -> requests++; throw original })
        }
        assertSame(original, caught)
        assertEquals(2, requests)
        assertTrue(ShadowLog.getLogsForTag("EtaVivoText").isEmpty())
    }

    @Test fun successfulCompletionDoesNotInvokeFailureObserver() {
        var requests = 0
        var observations = 0
        assertEquals("answer", ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "private-session",
            providerOverride = provider { _, _ -> requests++; response("answer") },
            onFailurePhase = { observations++ }))
        assertEquals(1, requests)
        assertEquals(0, observations)
    }

    @Test fun badConfigHasValidationPhaseWithoutInvokingProvider() {
        val phases = mutableListOf<ModelFeatureCompletion.FailurePhase>()
        assertThrows(IllegalArgumentException::class.java) {
            ModelFeatureCompletion.complete(config.copy(apiKey = ""), JSONArray(), AgentRunController(), "private-session",
                providerOverride = provider { _, _ -> fail("no provider request"); response("unused") },
                onFailurePhase = { phases.add(it) })
        }
        assertEquals(listOf(ModelFeatureCompletion.FailurePhase.CONFIG_VALIDATION), phases)
    }

    @Test fun fatalThrowableIsUnchangedEvenWhenObserverFails() {
        val original = AssertionError("private-fatal")
        val caught = assertThrows(AssertionError::class.java) {
            ModelFeatureCompletion.complete(config, JSONArray(), AgentRunController(), "private-session",
                providerOverride = provider { _, _ -> throw original },
                onFailurePhase = { throw IllegalStateException("private-observer") })
        }
        assertSame(original, caught)
    }
}
