package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.mangi.eta.data.repository.ConversationUsageTotals
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentHomeUiProjectionCompositionTest {
    @get:Rule val compose = createComposeRule()
    private val compositions = IntArray(4)

    @Test fun bodyDeltasRecomposeChatButNotRootSettingsOrShellAndRealChangesPropagate() {
        val home = mutableStateOf(AgentChatHomeUiState(
            messages = listOf(AgentMessageUi("reply", "body", isStreaming = true)),
            input = "", isStreaming = true, thinkingEnabled = true,
            providerId = "provider-a", modelId = "model-a",
        ))
        compose.setContent {
            // Same subscription pattern as AgentAppRoot: keep handles at root, read in consumers.
            val model = remember { homeUiProjectionState({ home.value }, ::appModelBinding) }
            val shell = remember { homeUiProjectionState({ home.value }, ::appShellHomeProjection) }
            SideEffect { compositions[0]++ }
            Column {
                SettingsProjection(model)
                ShellProjection(shell)
                ChatBody(home)
            }
        }
        val initial = compose.runOnIdle { compositions.copyOf() }
        repeat(3) { delta ->
            compose.runOnIdle {
                home.value = home.value.copy(messages = listOf(AgentMessageUi("reply", "delta-$delta", isStreaming = true)))
            }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(initial[0], compositions[0])
            assertEquals(initial[1], compositions[1])
            assertEquals(initial[2], compositions[2])
            assertTrue(compositions[3] > initial[3])
            home.value = home.value.copy(providerId = "provider-b", modelId = "model-b")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(compositions[1] > initial[1])
            assertEquals(initial[0], compositions[0])
            home.value = home.value.copy(isWaitingForCompression = true)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(compositions[2] > initial[2])
            assertEquals(initial[0], compositions[0])
        }
    }

    @Test fun rapidOwnerReturnHasSeparateStateAndLedgerNullZeroAndUpdatesStayWithOwner() {
        val source = mutableStateOf(ConversationUsageMessages("A", listOf(
            AgentMessageUi("a", "body", usage = TokenUsageUi(inputTokens = 12)),
        )))
        val ledgerA = MutableStateFlow<ConversationUsageTotals?>(null)
        val ledgerB = MutableStateFlow<ConversationUsageTotals?>(ConversationUsageTotals())
        lateinit var visible: State<ConversationTokenUsageUi>
        compose.setContent {
            val id = source.value.conversationId
            visible = rememberConversationUsageState(id, ledger = { if (it == "A") ledgerA else ledgerB }) { source.value }
            UsageProjection(visible)
        }
        compose.waitUntil(5000) { visible.value.inputTokens == 12L }
        val firstA = compose.runOnIdle { visible }
        compose.runOnIdle {
            source.value = ConversationUsageMessages("B", listOf(
                AgentMessageUi("b", "body", usage = TokenUsageUi(inputTokens = 99)),
            ))
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { visible.value == ConversationTokenUsageUi() }
        compose.runOnIdle { ledgerA.value = ConversationUsageTotals(24, 8, 4, 2) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ConversationTokenUsageUi(), visible.value)
            source.value = ConversationUsageMessages("A", listOf(
                AgentMessageUi("a", "new body", usage = TokenUsageUi(inputTokens = 13)),
            ))
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { visible.value == ConversationTokenUsageUi(24, 8, 4, 2) }
        compose.runOnIdle {
            assertNotSame(firstA, visible)
            ledgerA.value = ConversationUsageTotals()
        }
        compose.waitUntil(5000) { visible.value == ConversationTokenUsageUi() }
        compose.runOnIdle { ledgerA.value = null }
        compose.waitUntil(5000) { visible.value.inputTokens == 13L }
    }

    @Composable private fun SettingsProjection(state: State<AppModelBinding>) {
        val binding = state.value
        BasicText("${binding.providerId}/${binding.modelId}")
        SideEffect { compositions[1]++ }
    }

    @Composable private fun ShellProjection(state: State<AppShellHomeProjection>) {
        BasicText(state.value.isCompressingContext.toString())
        SideEffect { compositions[2]++ }
    }

    @Composable private fun ChatBody(state: State<AgentChatHomeUiState>) {
        BasicText((state.value.messages.single() as AgentMessageUi).content)
        SideEffect { compositions[3]++ }
    }

    @Composable private fun UsageProjection(state: State<ConversationTokenUsageUi>) {
        BasicText(state.value.totalTokens.toString())
    }
}
