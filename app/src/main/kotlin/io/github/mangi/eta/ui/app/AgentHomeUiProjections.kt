package io.github.mangi.eta.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.data.repository.ConversationUsageTotals
import io.github.mangi.eta.data.repository.UsageStatsRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/** Create the subscription without reading homeState in the caller's composition scope. */
internal fun <T> homeUiProjectionState(
    homeState: () -> AgentChatHomeUiState,
    project: (AgentChatHomeUiState) -> T,
): State<T> = derivedStateOf(structuralEqualityPolicy()) { project(homeState()) }

internal data class AppModelBinding(val providerId: String, val modelId: String)

internal fun appModelBinding(home: AgentChatHomeUiState) = AppModelBinding(home.providerId, home.modelId)

internal data class AppShellHomeProjection(
    val isCompressingContext: Boolean,
    val childStatusRoster: List<SubAgentContextStats>,
)

internal fun appShellHomeProjection(home: AgentChatHomeUiState) = AppShellHomeProjection(
    isCompressingContext = if (home.selectedContextTaskId == null) {
        home.isCompressingContext || home.isWaitingForCompression
    } else {
        home.childContexts.any { it.taskId == home.selectedContextTaskId &&
            (it.isCompacting || it.manualCompactionState == "pending") }
    },
    childStatusRoster = home.childStatusRoster,
)

/** Only the fields counted by the legacy fallback; never retains message bodies. */
internal data class ConversationUsageReceipt(val input: Long, val output: Long, val cached: Long)

internal fun conversationUsageReceipts(
    messages: List<AgentChatMessageUi>,
    checkActive: () -> Unit = {},
): List<ConversationUsageReceipt> = buildList {
    messages.forEachIndexed { index, message ->
        if (index % 128 == 0) checkActive()
        val receipt = when (message) {
            is AgentMessageUi -> message.usage?.let {
                ConversationUsageReceipt(
                    (it.inputTokens ?: 0).toLong(),
                    (it.outputTokens ?: 0).toLong(),
                    (it.cachedTokens ?: 0).toLong(),
                )
            }
            is ContextCompactedMessageUi -> message.preservedUsage.let {
                ConversationUsageReceipt(it.inputTokens, it.outputTokens, it.cachedTokens)
            }
            else -> null
        }
        // Adding a streaming placeholder or a zero receipt cannot change the total.
        if (receipt != null && receipt != ConversationUsageReceipt(0, 0, 0)) add(receipt)
    }
}

internal fun sumConversationUsageReceipts(
    receipts: List<ConversationUsageReceipt>,
    checkActive: () -> Unit = {},
): ConversationTokenUsageUi {
    var input = 0L
    var output = 0L
    var cached = 0L
    receipts.forEachIndexed { index, receipt ->
        if (index % 128 == 0) checkActive()
        input += receipt.input
        output += receipt.output
        cached += receipt.cached
    }
    // Match conversationTokenUsage: legacy messages have no cache-creation receipts.
    return ConversationTokenUsageUi(inputTokens = input, outputTokens = output, cachedTokens = cached)
}

/**
 * Snapshot observation, receipt projection and equality checks run off the UI thread.
 * Body deltas may require a background receipt scan, but never re-sum history or publish
 * new UI state when billing fields are unchanged. Latest work is cooperatively cancelled.
 */
internal data class ConversationUsageMessages(
    val conversationId: String?,
    val messages: List<AgentChatMessageUi>,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun fallbackConversationUsageFlow(
    conversationId: String?,
    messages: () -> ConversationUsageMessages,
): Flow<ConversationTokenUsageUi> = snapshotFlow {
    messages().takeIf { it.conversationId == conversationId }?.messages
}.filterNotNull()
    .mapLatest {
        val context = currentCoroutineContext()
        conversationUsageReceipts(it) { context.ensureActive() }
    }
    .distinctUntilChanged()
    .mapLatest {
        val context = currentCoroutineContext()
        sumConversationUsageReceipts(it) { context.ensureActive() }
    }
    .flowOn(Dispatchers.Default)

/** Ledger zero is authoritative too; a ledger update cancels the active fallback. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun conversationUsageUiFlow(
    conversationId: String?,
    ledger: Flow<ConversationUsageTotals?>,
    messages: () -> ConversationUsageMessages,
): Flow<ConversationTokenUsageUi> = ledger.distinctUntilChanged().transformLatest { recorded ->
    if (recorded != null) {
        emit(ConversationTokenUsageUi(recorded.input, recorded.output, recorded.cached, recorded.cacheCreation))
    } else {
        emitAll(fallbackConversationUsageFlow(conversationId, messages))
    }
}.flowOn(Dispatchers.Default)

/** A small last-known cache avoids flashing zero on a quick A -> B -> A return. */
internal class ConversationUsageUiCache {
    private val values = LinkedHashMap<String, ConversationTokenUsageUi>()

    fun initial(conversationId: String?): ConversationTokenUsageUi =
        conversationId?.let { values[it] } ?: ConversationTokenUsageUi()

    fun record(conversationId: String?, usage: ConversationTokenUsageUi) {
        // Null represents a draft, not a stable identity; never reuse across new drafts.
        if (conversationId == null) return
        values.remove(conversationId)
        values[conversationId] = usage
        if (values.size > 4) values.remove(values.keys.first())
    }
}

/**
 * The root holds this State but only RoutedShell reads its value. A separate State per
 * selection prevents a cancelled old collector from publishing into the new owner.
 * Snapshot owner checks also prevent the old fallback from reading a new conversation
 * during the gap before effect cancellation. First-time loading uses an empty placeholder;
 * returning conversations reuse only their own last-known total until refreshed.
 */
@Composable
internal fun rememberConversationUsageState(
    conversationId: String?,
    ledger: (String?) -> Flow<ConversationUsageTotals?> = UsageStatsRepository::conversationUsageFlow,
    messages: () -> ConversationUsageMessages,
): State<ConversationTokenUsageUi> {
    val cache = remember { ConversationUsageUiCache() }
    val result = remember(conversationId) { mutableStateOf(cache.initial(conversationId)) }
    val currentMessages = rememberUpdatedState(messages)
    val currentLedger = rememberUpdatedState(ledger)
    LaunchedEffect(conversationId, result) {
        conversationUsageUiFlow(conversationId, currentLedger.value(conversationId)) {
            currentMessages.value()
        }.collect { usage ->
            currentCoroutineContext().ensureActive()
            if (currentMessages.value().conversationId == conversationId) {
                cache.record(conversationId, usage)
                result.value = usage
            }
        }
    }
    return result
}
