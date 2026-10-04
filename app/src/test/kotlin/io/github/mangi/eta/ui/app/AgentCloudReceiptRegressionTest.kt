package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.liveContextUsage
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
class AgentCloudReceiptRegressionTest {
    @Test fun largeCloudIncreasesAdvanceTheRingEvenWhenLocalGrowthIsSmall() {
        withApp { app ->
            // Actual cloud receipts; local counts below are deliberately small test fixtures.
            val inputs = listOf(23_104, 54_244, 76_783, 106_018, 133_457, 158_537, 270_648)
            inputs.forEachIndexed { index, input ->
                val round = index + 4
                val historyTokens = 10_000 + index * 1_000
                send(app, AgentEvent.ProviderRequestStarted(round))
                send(app, AgentEvent.UsageReceived(round, AgentTokenUsage(
                    inputTokens = input, outputTokens = 271,
                    cachedTokens = if (input == 270_648) 268_320 else 0),
                    requestHistoryTokens = historyTokens, requestOverheadTokens = 10_000))
                assertEquals(input, state(app).livePromptTokens)
                assertFalse(state(app).livePromptIsProjected)
                assertEquals(historyTokens, state(app).cloudHistoryTokens)
                assertEquals(10_000, state(app).cloudRequestOverheadTokens)
                // Keeping projections from overwriting a real bill is still intentional.
                send(app, AgentEvent.UsageReceived(round,
                    AgentTokenUsage(inputTokens = historyTokens + 10_000), projected = true))
                assertEquals(input, state(app).livePromptTokens)
                assertEquals(historyTokens, state(app).cloudHistoryTokens)
            }
            val current = state(app)
            val ring = liveContextUsage(selectedModel = null,
                billedContextTokens = current.livePromptTokens, activeRunContextWindow = 272_000)
            assertEquals(270_648, ring.contextTokens)
            assertEquals(272_000, ring.contextWindow)
            assertFalse(ring.estimated)
            val usage = current.messages.filterIsInstance<AgentMessageUi>().last().usage!!
            assertEquals(270_648, usage.inputTokens)
            assertEquals(268_320, usage.cachedTokens)
            assertEquals(271, usage.outputTokens)
        }
    }

    @Test fun missingPromptAndStalePreCompactionReceiptsCannotReplaceTheAnchor() {
        withApp { app ->
            send(app, AgentEvent.UsageReceived(26, AgentTokenUsage(inputTokens = 270_648),
                requestHistoryTokens = 30_000, requestOverheadTokens = 10_000))
            send(app, AgentEvent.UsageReceived(26, AgentTokenUsage(outputTokens = 271),
                requestHistoryTokens = 31_000, requestOverheadTokens = 10_000))
            assertEquals(270_648, state(app).livePromptTokens)
            assertEquals(30_000, state(app).cloudHistoryTokens)

            send(app, AgentEvent.ContextCompacted(27, true, 100, 10,
                history = listOf(AgentModelClient.ConversationMessage("system", "summary")),
                compressorLabel = "摘要压缩"))
            assertNull(state(app).livePromptTokens)
            send(app, AgentEvent.UsageReceived(26, AgentTokenUsage(inputTokens = 270_648)))
            assertNull(state(app).livePromptTokens)
            send(app, AgentEvent.UsageReceived(27, AgentTokenUsage(inputTokens = 15_000),
                requestHistoryTokens = 5_000, requestOverheadTokens = 10_000))
            assertEquals(15_000, state(app).livePromptTokens)
            assertFalse(state(app).livePromptIsProjected)
        }
    }

    @Test fun partialUsageKeepsOnlySameRequestBaselineAndNewRequestCannotBorrowIt() {
        withApp { app ->
            send(app, AgentEvent.ProviderRequestStarted(1))
            send(app, AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 37214),
                requestHistoryTokens = 481, requestOverheadTokens = 25270))
            send(app, AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 37214, outputTokens = 10)))
            assertEquals(481, state(app).cloudHistoryTokens)
            assertEquals(25270, state(app).cloudRequestOverheadTokens)
            send(app, AgentEvent.ProviderRequestStarted(2))
            send(app, AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 40000)))
            assertEquals(40000, state(app).livePromptTokens)
            assertNull(state(app).cloudHistoryTokens)
            assertNull(state(app).cloudRequestOverheadTokens)
            // A repeated start is not new evidence and must not break same-request corrections.
            send(app, AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 40000),
                requestHistoryTokens = 1000, requestOverheadTokens = 25270))
            send(app, AgentEvent.ProviderRequestStarted(2))
            send(app, AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 40000)))
            assertEquals(1000, state(app).cloudHistoryTokens)
            assertEquals(25270, state(app).cloudRequestOverheadTokens)
        }
    }

    private fun withApp(block: (AgentAppState) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val app = AgentAppState(context, scope)
            call(app, "updateSelectionProviders", listOf(OpenAiCompatibleProviderSetting(
                "p", "Test", "https://example.org/v1",
                models = listOf(Model("m", "model", "Model", contextWindow = 272_000)))))
            call(app, "updateConversation", "cloud-c", AgentChatHomeUiState(
                messages = listOf(UserMessageUi("cloud-user", "original")),
                history = listOf(AgentModelClient.ConversationMessage("user", "original")),
                input = "", isStreaming = true, thinkingEnabled = false,
                providerId = "p", modelId = "m"), false, true)
            call(app, "bindUsageRun", "cloud-r", "cloud-c")
            @Suppress("UNCHECKED_CAST")
            val windows = app.javaClass.getDeclaredField("runContextWindows")
                .apply { isAccessible = true }.get(app) as MutableMap<String, Int>
            windows["cloud-r"] = 272_000
            block(app)
        } finally {
            scope.cancel()
            EtaDatabase.closeForTests()
        }
    }

    private fun send(app: AgentAppState, event: AgentEvent) {
        call(app, "applyRunEvent", "cloud-r", event, false, true)
    }

    private fun state(app: AgentAppState): AgentChatHomeUiState =
        call(app, "conversationStateForRun", "cloud-r") as AgentChatHomeUiState

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
}
