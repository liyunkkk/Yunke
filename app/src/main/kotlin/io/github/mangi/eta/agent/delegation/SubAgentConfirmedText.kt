package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent

/** Only completed public text blocks are retained. Never archive thinking or an unfinished stream. */
internal class SubAgentConfirmedText(private val limit: Int = 16000) {
    private var block: Pair<Int, Int>? = null
    private val pending = StringBuilder()
    private val confirmed = StringBuilder()
    private var pendingTruncated = false
    var truncated = false
        private set

    private fun safePrefix(text: String, capacity: Int): String {
        var end = minOf(text.length, capacity.coerceAtLeast(0))
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return text.substring(0, end)
    }

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantBlockStart -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                block = event.round to event.index
                pending.setLength(0)
                pendingTruncated = false
            }
            is AgentEvent.AssistantBlockDelta -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                val key = event.round to event.index
                if (block != key) { block = key; pending.setLength(0); pendingTruncated = false }
                val capacity = (limit - pending.length).coerceAtLeast(0)
                pending.append(event.delta.take(capacity))
                if (event.delta.length > capacity) pendingTruncated = true
            }
            is AgentEvent.AssistantBlockEnd -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                val text = event.replacementContent ?: if (block == (event.round to event.index)) pending.toString() else ""
                if (text.isNotBlank() && confirmed.length < limit) {
                    if (confirmed.isNotEmpty()) confirmed.append('\n')
                    val prefix = safePrefix(text, limit - confirmed.length)
                    confirmed.append(prefix)
                    if (prefix.length < text.length || (event.replacementContent == null && pendingTruncated)) truncated = true
                } else if (text.isNotBlank()) truncated = true
                block = null
                pending.setLength(0)
            }
            else -> Unit
        }
    }

    fun value(): String = confirmed.toString()
}
