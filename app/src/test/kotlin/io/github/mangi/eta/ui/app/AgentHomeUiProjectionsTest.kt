package io.github.mangi.eta.ui.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.data.repository.ConversationUsageTotals
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.conversationTokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AgentHomeUiProjectionsTest {
    @Test fun bodyAndThinkingDeltasDoNotChangeNonChatProjectionsButModelChangesDo() {
        val home = mutableStateOf(homeFixture())
        val model = homeUiProjectionState({ home.value }, ::appModelBinding)
        val shell = homeUiProjectionState({ home.value }, ::appShellHomeProjection)
        val assistant = homeUiProjectionState({ home.value }) { it.assistantId }
        val originalModel = model.value
        val originalShell = shell.value
        val originalAssistant = assistant.value
        home.value = home.value.copy(messages = listOf(
            AgentMessageUi("reply", "new body delta", isStreaming = true),
            ThinkingMessageUi("thinking", "new reasoning delta", isStreaming = true),
        ))
        assertEquals(originalModel, model.value)
        assertEquals(originalShell, shell.value)
        assertEquals(originalAssistant, assistant.value)
        home.value = home.value.copy(providerId = "provider-b", modelId = "model-b")
        assertEquals(AppModelBinding("provider-b", "model-b"), model.value)
        assertNotEquals(originalModel, model.value)
        home.value = home.value.copy(assistantId = "assistant-b")
        assertEquals("assistant-b", assistant.value)
    }

    @Test fun mainAndChildCompressionSelectionAndRosterStillPropagate() {
        val child = SubAgentContextStats("child", 1, "research", "model", "Model", "Provider", 10000)
        val base = homeFixture().copy(childContexts = listOf(child), childStatusRoster = listOf(child))
        assertEquals(false, appShellHomeProjection(base).isCompressingContext)
        assertEquals(true, appShellHomeProjection(base.copy(isWaitingForCompression = true)).isCompressingContext)
        assertEquals(true, appShellHomeProjection(base.copy(isCompressingContext = true)).isCompressingContext)
        val selected = base.copy(selectedContextTaskId = "child", isCompressingContext = true)
        assertEquals(false, appShellHomeProjection(selected).isCompressingContext)
        assertEquals(true, appShellHomeProjection(selected.copy(
            childContexts = listOf(child.copy(manualCompactionState = "pending")),
        )).isCompressingContext)
        assertEquals(true, appShellHomeProjection(selected.copy(
            childContexts = listOf(child.copy(isCompacting = true)),
        )).isCompressingContext)
        val stopped = child.copy(status = "completed", statusVersion = 1)
        assertEquals(listOf(stopped), appShellHomeProjection(base.copy(childStatusRoster = listOf(stopped))).childStatusRoster)
    }

    @Test fun receiptsIgnoreTextButTrackUsageEditsDeletionAndCompactionLikeLegacyFallback() {
        val answer = AgentMessageUi("answer", "body", usage = TokenUsageUi(inputTokens = 30, outputTokens = 7, cachedTokens = 10))
        val marker = ContextCompactedMessageUi("compact", 8, "summary", preservedUsage = ConversationTokenUsageUi(100, 20, 40))
        val messages = listOf<AgentChatMessageUi>(UserMessageUi("user", "question"), marker, answer)
        val receipts = conversationUsageReceipts(messages)
        assertEquals(receipts, conversationUsageReceipts(listOf(marker.copy(summary = "summary delta"), answer.copy(content = "body delta"))))
        assertEquals(conversationTokenUsage(messages), sumConversationUsageReceipts(receipts))
        val variants = listOf(
            messages + AgentMessageUi("streaming", "delta", isStreaming = true),
            messages + answer.copy(id = "second", usage = TokenUsageUi(inputTokens = 9, outputTokens = 2)),
            listOf(marker, answer.copy(usage = TokenUsageUi(inputTokens = 31, outputTokens = 8, cachedTokens = 11))),
            listOf(marker),
            emptyList(),
        )
        variants.forEach { variant ->
            assertEquals(conversationTokenUsage(variant), sumConversationUsageReceipts(conversationUsageReceipts(variant)))
        }
    }

    @Test fun receiptScanAndSumAreCooperativelyCancellable() {
        val messages = List(1024) { AgentMessageUi("$it", "body", usage = TokenUsageUi(inputTokens = 1)) }
        var checks = 0
        try {
            conversationUsageReceipts(messages) { if (++checks == 2) throw CancellationException("cancelled") }
            fail("scan must propagate cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, checks)
        }
        checks = 0
        try {
            sumConversationUsageReceipts(List(1024) { ConversationUsageReceipt(1, 1, 0) }) {
                if (++checks == 2) throw CancellationException("cancelled")
            }
            fail("sum must propagate cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, checks)
        }
    }

    @Test fun fallbackPublishesNoBodyDeltaTotalsAndRejectsWrongOwnerOnQuickAToBToA() = runBlocking {
        val source = mutableStateOf(usageMessages("A", 12))
        val results = Channel<ConversationTokenUsageUi>(Channel.UNLIMITED)
        val worker = launch(Dispatchers.Default) {
            fallbackConversationUsageFlow("A") { source.value }.collect { results.send(it) }
        }
        try {
            assertEquals(12L, withTimeout(5000) { results.receive() }.inputTokens)
            Snapshot.withMutableSnapshot { source.value = usageMessages("A", 12, "body delta") }
            Snapshot.sendApplyNotifications()
            assertNull(withTimeoutOrNull(200) { results.receive() })
            Snapshot.withMutableSnapshot { source.value = usageMessages("B", 99) }
            Snapshot.sendApplyNotifications()
            assertNull(withTimeoutOrNull(200) { results.receive() })
            Snapshot.withMutableSnapshot { source.value = usageMessages("A", 13) }
            Snapshot.sendApplyNotifications()
            assertEquals(13L, withTimeout(5000) { results.receive() }.inputTokens)
        } finally {
            worker.cancelAndJoin()
        }
    }

    @Test fun ledgerNullUsesFallbackZeroIsAuthoritativeAndUpdatesCancelFallback() = runBlocking {
        val source = mutableStateOf(usageMessages("A", 12))
        val ledger = MutableStateFlow<ConversationUsageTotals?>(null)
        val results = Channel<ConversationTokenUsageUi>(Channel.UNLIMITED)
        val worker = launch(Dispatchers.Default) {
            conversationUsageUiFlow("A", ledger) { source.value }.collect { results.send(it) }
        }
        try {
            assertEquals(12L, withTimeout(5000) { results.receive() }.inputTokens)
            ledger.value = ConversationUsageTotals()
            assertEquals(ConversationTokenUsageUi(), withTimeout(5000) { results.receive() })
            Snapshot.withMutableSnapshot { source.value = usageMessages("A", 999) }
            Snapshot.sendApplyNotifications()
            assertNull(withTimeoutOrNull(200) { results.receive() })
            ledger.value = ConversationUsageTotals(40, 8, 5, 3)
            assertEquals(ConversationTokenUsageUi(40, 8, 5, 3), withTimeout(5000) { results.receive() })
            ledger.value = null
            assertEquals(999L, withTimeout(5000) { results.receive() }.inputTokens)
            Snapshot.withMutableSnapshot { source.value = usageMessages("A", 1000) }
            Snapshot.sendApplyNotifications()
            assertEquals(1000L, withTimeout(5000) { results.receive() }.inputTokens)
        } finally {
            worker.cancelAndJoin()
        }
    }

    @Test fun lastKnownTotalsAreBoundedAndNeverReusedForAnotherConversationOrDraft() {
        val cache = ConversationUsageUiCache()
        cache.record("A", ConversationTokenUsageUi(12))
        cache.record("B", ConversationTokenUsageUi(99))
        assertEquals(12L, cache.initial("A").inputTokens)
        assertEquals(99L, cache.initial("B").inputTokens)
        assertEquals(ConversationTokenUsageUi(), cache.initial("C"))
        cache.record(null, ConversationTokenUsageUi(999))
        assertEquals(ConversationTokenUsageUi(), cache.initial(null))
        listOf("C", "D", "E").forEach { cache.record(it, ConversationTokenUsageUi(1)) }
        assertEquals(ConversationTokenUsageUi(), cache.initial("A"))
    }

    private fun homeFixture() = AgentChatHomeUiState(
        messages = listOf(AgentMessageUi("reply", "body", isStreaming = true)),
        input = "", isStreaming = true, thinkingEnabled = true,
        providerId = "provider-a", modelId = "model-a", assistantId = "assistant-a",
    )

    private fun usageMessages(id: String, input: Int, body: String = "body") = ConversationUsageMessages(
        id, listOf(AgentMessageUi("reply", body, usage = TokenUsageUi(inputTokens = input))),
    )
}
