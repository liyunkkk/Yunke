package io.github.mangi.eta.data.repository

import org.json.JSONObject

/** Lifetime conversation totals survive detail trimming and message deletion. */
internal data class ConversationUsageTotals(val input: Long = 0, val output: Long = 0, val cached: Long = 0) {
    operator fun plus(other: ConversationUsageTotals) = ConversationUsageTotals(
        input + other.input, output + other.output, cached + other.cached)
}

private const val CONVERSATION_TOTALS = "conversationTotalsV1"

internal fun conversationUsageTotals(raw: String?, id: String?): ConversationUsageTotals? {
    if (id.isNullOrBlank()) return ConversationUsageTotals()
    val item = runCatching { JSONObject(raw.orEmpty()).optJSONObject(CONVERSATION_TOTALS)?.optJSONObject(id) }.getOrNull()
        ?: return null
    return ConversationUsageTotals(item.optLong("in"), item.optLong("out"), item.optLong("k"))
}

/** One-time migration. Old message totals overlap old ledger events; NEVER add the two totals. */
internal fun seedConversationUsage(raw: String?, legacy: Map<String, ConversationUsageTotals>): String {
    val root = JSONObject(raw?.takeIf { it.isNotBlank() } ?: "{}")
    val totals = root.optJSONObject(CONVERSATION_TOTALS) ?: JSONObject().also { root.put(CONVERSATION_TOTALS, it) }
    if (legacy.isEmpty() && root.optBoolean("conversationTotalsInitialized")) return root.toString()
    val known = mutableMapOf<String, ConversationUsageTotals>()
    decodeModelUsageSnapshot(raw).providers.forEach { provider -> provider.models.forEach { model ->
        model.events.collapsedByRound().forEach { event ->
            event.conversationId?.takeIf { it.isNotBlank() }?.let { id ->
                known[id] = (known[id] ?: ConversationUsageTotals()) +
                    ConversationUsageTotals(event.inputTokens, event.outputTokens, event.cachedTokens)
            }
        }
    } }
    (legacy.keys + known.keys).forEach { id ->
        if (!totals.has(id)) {
            val messages = legacy[id] ?: ConversationUsageTotals()
            val events = known[id] ?: ConversationUsageTotals()
            // Preserve the largest actually recorded cumulative value per field; missing history is not estimated.
            totals.put(id, JSONObject().put("in", maxOf(messages.input, events.input))
                .put("out", maxOf(messages.output, events.output)).put("k", maxOf(messages.cached, events.cached)))
        }
    }
    root.put("conversationTotalsInitialized", true)
    return root.toString()
}

internal fun updateConversationUsage(root: JSONObject, incoming: ModelUsageEvent, previous: ModelUsageEvent?) {
    val id = incoming.conversationId?.takeIf { it.isNotBlank() } ?: return
    val totals = root.optJSONObject(CONVERSATION_TOTALS) ?: JSONObject().also { root.put(CONVERSATION_TOTALS, it) }
    val item = totals.optJSONObject(id) ?: JSONObject().also { totals.put(id, it) }
    item.put("in", item.optLong("in") + incoming.inputTokens - (previous?.inputTokens ?: 0))
    item.put("out", item.optLong("out") + incoming.outputTokens - (previous?.outputTokens ?: 0))
    item.put("k", item.optLong("k") + incoming.cachedTokens - (previous?.cachedTokens ?: 0))
}
