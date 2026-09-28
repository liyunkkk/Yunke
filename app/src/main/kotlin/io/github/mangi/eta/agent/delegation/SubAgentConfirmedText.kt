package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent

/** Only completed public text blocks are retained. Never archive thinking or an unfinished stream. */
internal class SubAgentConfirmedText(private val limit: Int = 16000) {
    private var block: Pair<Int, Int>? = null
    private val pending = StringBuilder()
    private val confirmed = StringBuilder()

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantBlockStart -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                block = event.round to event.index
                pending.setLength(0)
            }
            is AgentEvent.AssistantBlockDelta -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                val key = event.round to event.index
                if (block != key) { block = key; pending.setLength(0) }
                pending.append(event.delta.take((limit - pending.length).coerceAtLeast(0)))
            }
            is AgentEvent.AssistantBlockEnd -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                val text = event.replacementContent ?: if (block == (event.round to event.index)) pending.toString() else ""
                if (text.isNotBlank() && confirmed.length < limit) {
                    if (confirmed.isNotEmpty()) confirmed.append('\n')
                    confirmed.append(text.take((limit - confirmed.length).coerceAtLeast(0)))
                }
                block = null
                pending.setLength(0)
            }
            else -> Unit
        }
    }

    fun value(): String = confirmed.toString()
}
