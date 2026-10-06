package io.github.mangi.eta.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Settings changes can re-emit an unchanged ledger. Do not parse those copies,
 * and keep the full JSON read off the UI collector's dispatcher. Changed raw
 * inputs still emit in order, even when this conversation's totals are equal.
 */
internal fun conversationUsageTotalsFlow(
    rawUsage: Flow<String>,
    conversationId: String?,
): Flow<ConversationUsageTotals?> = rawUsage
    .distinctUntilChanged()
    .map { raw -> conversationUsageTotals(raw, conversationId) }
    .flowOn(Dispatchers.Default)
