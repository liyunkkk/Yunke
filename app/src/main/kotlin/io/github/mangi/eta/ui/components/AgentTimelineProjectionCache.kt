package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi

/**
 * Cache only the last projection, not individual historical messages forever.
 *
 * The fast path accepts payload replacement of AgentMessageUi at the SAME source
 * slot and ID, with every other source object unchanged. Terminal ordering reads
 * an assistant's ID/type, not its body or streaming flags, and assistants are
 * individual Message entries rather than work-group members. Every structural
 * change and every non-assistant replacement uses the original full projection.
 * Duplicate source IDs disable the fast path: ordering may select a later payload
 * or synthesize a notice ID, so a map keyed only by ID would be unsafe.
 */
internal class AgentTimelineProjectionCache(
    private val fullProjection: (List<AgentChatMessageUi>) -> List<AgentTimelineEntry> = { it.toTimelineEntries() },
) {
    private var source: List<AgentChatMessageUi>? = null
    private var entries: List<AgentTimelineEntry> = emptyList()
    private var sourceToEntry: IntArray? = null

    fun project(messages: List<AgentChatMessageUi>): List<AgentTimelineEntry> {
        val previous = source
        val mapping = sourceToEntry
        if (previous != null && mapping != null && previous.size == messages.size) {
            var changed = false
            var compatible = true
            for (index in messages.indices) {
                val old = previous[index]
                val current = messages[index]
                if (old === current) continue
                if (old !is AgentMessageUi || current !is AgentMessageUi ||
                    old.id != current.id || mapping[index] < 0
                ) {
                    compatible = false
                    break
                }
                changed = true
            }
            if (compatible) {
                if (!changed) return entries
                val updated = entries.toMutableList()
                for (index in messages.indices) {
                    if (previous[index] !== messages[index]) {
                        updated[mapping[index]] = AgentTimelineEntry.Message(messages[index])
                    }
                }
                // Neither the old input snapshot nor any previously returned list
                // is mutated. Also tolerate a caller reusing its list container.
                source = messages.toList()
                entries = updated
                return updated
            }
        }

        val input = messages.toList()
        val projected = fullProjection(input)
        source = input
        entries = projected
        sourceToEntry = mapAssistantSlots(input, projected)
        return projected
    }

    private fun mapAssistantSlots(
        input: List<AgentChatMessageUi>,
        projected: List<AgentTimelineEntry>,
    ): IntArray? {
        val byId = HashMap<String, Int>(input.size)
        input.forEachIndexed { index, message ->
            if (byId.put(message.id, index) != null) return null
        }
        val mapping = IntArray(input.size) { -1 }
        projected.forEachIndexed { entryIndex, entry ->
            val message = (entry as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                ?: return@forEachIndexed
            val sourceIndex = byId[message.id] ?: return null
            if (input[sourceIndex] !== message || mapping[sourceIndex] >= 0) return null
            mapping[sourceIndex] = entryIndex
        }
        // Fail closed if projection changes ever stop preserving assistant refs.
        input.forEachIndexed { index, message ->
            if (message is AgentMessageUi && mapping[index] < 0) return null
        }
        return mapping
    }
}
