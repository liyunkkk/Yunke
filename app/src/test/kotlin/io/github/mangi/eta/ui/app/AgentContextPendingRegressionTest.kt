package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.RequestOverheadCalibrationStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.ui.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentContextPendingRegressionTest {
    @Test fun firstPendingStaysNoneAndEveryTerminalResultConsumesItEvenWithoutARequest() = fixture { f ->
        for ((index, outcome) in listOf(
            AgentRuntimeWire.RunResult("", true, "done"),
            AgentRuntimeWire.RunResult("", false, "", "failed"),
            AgentRuntimeWire.RunResult("", false, "", "已停止"),
            AgentRuntimeWire.RunResult("", true, ""),
        ).withIndex()) {
            for (requestStarted in listOf(false, true)) {
                val id = "first-$index-$requestStarted"
                val run = "run-$id"
                f.put(id, f.pending())
                f.bind(run, id)
                assertFalse(f.state(id).contextHasStarted)
                assertEquals("无", formatContextUsage(f.usage(id)))
                if (requestStarted) f.send(run, AgentEvent.ProviderRequestStarted(1))
                assertEquals("无", formatContextUsage(f.usage(id)))
                call(f.app, "applyRunResult", run, outcome.copy(runId = run), false)
                assertTrue(f.state(id).contextHasStarted)
                assertEquals("未知", formatContextUsage(f.usage(id)))
                assertNull(f.usage(id).progress)
            }
        }
    }

    @Test fun selectingModelOnEmptyConversationDoesNotConsumeFirstTurn() = fixture { f ->
        val empty = f.pending().copy(history = emptyList(), messages = emptyList(), isStreaming = false,
            providerId = "", modelId = "")
        f.put("empty", empty)
        f.put("empty", empty.copy(providerId = f.provider.id, modelId = "m"))
        assertFalse(f.state("empty").contextHasStarted)
        assertFalse(f.state("empty").contextAwaitingReceipt)
        assertEquals("无", formatContextUsage(f.usage("empty")))
        f.put("empty", f.state("empty").copy(modelId = "other"))
        assertEquals("无", formatContextUsage(f.usage("empty")))
    }

    @Test fun providerAndRetryBoundariesRevokeActualButKeepIndependentBudgetAnchor() = fixture { f ->
        f.put("c", f.pending())
        f.bind("r", "c")
        f.send("r", AgentEvent.ProviderRequestStarted(1))
        val oldHistory = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        f.send("r", receipt(1, oldHistory))
        assertEquals(15000, f.usage("c").contextTokens)
        assertFalse(f.usage("c").estimated)
        f.put("c", f.state("c").copy(history = f.state("c").history +
            AgentModelClient.ConversationMessage("assistant", "more history")))
        f.send("r", AgentEvent.ProviderRequestStarted(2))
        val pending = f.state("c")
        assertNull(pending.livePromptTokens)
        assertNull(pending.contextReceiptEvidence)
        assertNull(pending.cloudReceiptRequestId)
        assertEquals(15000, pending.contextBudgetReceiptTokens)
        assertEquals(oldHistory, pending.cloudHistoryTokens)
        assertEquals(10000, pending.cloudRequestOverheadTokens)
        assertTrue(f.usage("c").estimated)
        assertTrue(formatContextUsage(f.usage("c")).startsWith("≈"))
        assertEquals(RequestOverheadCalibration.receiptEstimate(15000, oldHistory, 10000,
            pending.history.sumOf { AgentContextBudget.countMessage(it) }, 10000), f.usage("c").contextTokens)
        assertEquals(15000, call(f.app, "budgetReceiptTokens", pending))
        assertEquals(15000, call(f.app, "budgetReceiptTokens", pending.copy(receiptPredictionTokens = 99999)))
        assertNull(call(f.app, "billedPromptTokens", pending))
        f.send("r", AgentEvent.ModelRetryScheduled(2, 1, 3, 1000, "NETWORK"))
        assertNull(f.state("c").livePromptTokens)
        assertTrue(f.usage("c").estimated)
        assertEquals(15000, f.state("c").contextBudgetReceiptTokens)
        f.send("r", AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 90000), projected = true))
        assertNull(f.state("c").livePromptTokens)
        assertEquals(pending.receiptPredictionTokens, f.state("c").receiptPredictionTokens)
        f.send("r", receipt(2, oldHistory, input = 16000))
        assertEquals(16000, f.state("c").contextBudgetReceiptTokens)
        assertEquals(16000, f.usage("c").contextTokens)
        assertNull(f.state("c").receiptPredictionTokens)
        assertFalse(f.usage("c").estimated)
        // Same-round retry must not merge an old request's partial local basis.
        f.send("r", AgentEvent.ModelRetryScheduled(2, 2, 3, 1000, "NETWORK"))
        f.send("r", AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 16000), requestHistoryTokens = oldHistory))
        assertNull(f.state("c").cloudRequestOverheadTokens)
        assertNull(f.state("c").contextBudgetReceiptTokens)
    }

    @Test fun routeChangeAndIncompatibleHistoryCannotReuseOldReceiptEstimate() = fixture { f ->
        f.put("c", f.pending())
        f.bind("r", "c")
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        f.send("r", receipt(1, history))
        f.put("c", f.state("c").copy(history = listOf(AgentModelClient.ConversationMessage("user", "x".repeat(100000)))))
        f.send("r", AgentEvent.ProviderRequestStarted(2))
        assertEquals("未知", formatContextUsage(f.usage("c")))
        assertEquals(15000, f.state("c").contextBudgetReceiptTokens)
        f.providers(f.provider.copy(baseUrl = "https://other.example/v1"))
        f.send("r", AgentEvent.ProviderRequestStarted(3))
        assertNull(f.state("c").livePromptTokens)
        assertNull(f.state("c").contextBudgetReceiptTokens)
        assertNull(f.state("c").receiptPredictionTokens)
        assertEquals("未知", formatContextUsage(f.usage("c")))
    }

    @Test fun sameRoundZeroOverheadCorrectionRevokesPersistedLearningAndFreshBasisRepairsIt() = fixture { f ->
        f.put("c", f.pending())
        f.bind("zero-run", "c")
        val history = f.state("c").history.sumOf { AgentContextBudget.countMessage(it) }
        for (round in 1..3) {
            f.send("zero-run", AgentEvent.ProviderRequestStarted(round))
            f.send("zero-run", receipt(round, history))
        }
        val stable = requireNotNull(RequestOverheadCalibrationStore.read(f.provider.id, "m"))
        assertNotNull(stable.ratio)
        f.send("zero-run", receipt(3, history, overhead = 0))
        val revoked = requireNotNull(RequestOverheadCalibrationStore.read(f.provider.id, "m"))
        assertEquals(stable.observations.map { it.requestId }, revoked.observations.map { it.requestId })
        assertEquals(3, revoked.samples)
        assertFalse(revoked.observations.last().complete)
        assertNull(revoked.ratio)
        assertEquals(15000, f.usage("c").contextTokens) // still a genuine cloud receipt
        f.send("zero-run", receipt(3, history))
        assertEquals(stable, RequestOverheadCalibrationStore.read(f.provider.id, "m"))
    }

    private fun receipt(round: Int, history: Int, input: Int = 15000, overhead: Int = 10000) =
        AgentEvent.UsageReceived(round, AgentTokenUsage(inputTokens = input),
            requestHistoryTokens = history, requestOverheadTokens = overhead)

    private class Fixture(val app: AgentAppState) {
        val provider = OpenAiCompatibleProviderSetting("pending-test", "Test", "https://example.org/v1",
            models = listOf(Model("m", "model", "Model", contextWindow = 100000), Model("other", "other", "Other")))
        init { providers(provider) }
        fun providers(provider: OpenAiCompatibleProviderSetting) {
            app.javaClass.getDeclaredField("selectionProviders").apply { isAccessible = true }.set(app, listOf(provider))
        }
        fun pending() = AgentChatHomeUiState(messages = listOf(UserMessageUi("u", "question")),
            history = listOf(AgentModelClient.ConversationMessage("user", "question")),
            input = "", isStreaming = true, thinkingEnabled = false, providerId = provider.id, modelId = "m")
        fun put(id: String, state: AgentChatHomeUiState) { call(app, "updateConversation", id, state, false) }
        fun bind(run: String, id: String) { call(app, "bindUsageRun", run, id) }
        fun state(id: String) = call(app, "conversationState", id) as AgentChatHomeUiState
        fun send(run: String, event: AgentEvent) { call(app, "applyRunEvent", run, event, false, true) }
        fun usage(id: String): AgentContextUsageUi {
            val state = state(id)
            return liveContextUsage(state.history, "", emptyList(), null,
                billedContextTokens = state.livePromptTokens.takeUnless { state.livePromptIsProjected },
                contextDisplayPolicy = contextDisplayPolicy(state), receiptEstimateTokens = state.receiptPredictionTokens)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        // Only exercise synchronous reducers: no runtime, persistence, or observer jobs escape the fixture.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        try { block(Fixture(AgentAppState(context, scope))) }
        finally { scope.cancel(); EtaDatabase.closeForTests() }
    }

    companion object {
        private fun call(target: Any, name: String, vararg args: Any?): Any? =
            target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }.invoke(target, *args)
    }
}
