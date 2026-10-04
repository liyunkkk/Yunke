package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import java.util.RandomAccess

/** All boundaries are synchronous: never render new controls against an old message snapshot. */
internal data class AgentChatProjectionContext(
    val conversationId: String?,
    val editTargetMessageId: String?,
    val isStreaming: Boolean,
    val isPaused: Boolean = false,
    val isCompressingContext: Boolean = false,
    val isWaitingForCompression: Boolean = false,
) {
    val allowsTailUpdate: Boolean
        get() = isStreaming && !isPaused && !isCompressingContext &&
            !isWaitingForCompression && editTargetMessageId == null
}

internal data class AgentTimelineMetadata(
    val visibleMessageIds: Set<String>,
    val workGroups: Map<String, AgentTimelineEntry.WorkProcess>,
)

internal fun timelineMetadata(
    visibleMessages: List<AgentChatMessageUi>,
    entries: List<AgentTimelineEntry>,
): AgentTimelineMetadata = AgentTimelineMetadata(
    visibleMessageIds = visibleMessages.mapTo(HashSet()) { it.id },
    workGroups = buildMap {
        entries.forEach { entry ->
            if (entry is AgentTimelineEntry.WorkProcess) put(entry.key, entry)
        }
    },
)

internal class AgentChatTimelineSnapshot(
    val sourceMessages: List<AgentChatMessageUi>,
    val context: AgentChatProjectionContext,
    val visibleMessages: List<AgentChatMessageUi>,
    val entries: List<AgentTimelineEntry>,
    val metadata: AgentTimelineMetadata,
    // Always the last full projection, never another overlay: no growing delegation chain.
    val visibleBase: List<AgentChatMessageUi> = visibleMessages,
    val entriesBase: List<AgentTimelineEntry> = entries,
)

/** Avoid copying the full list when it contains no empty assistant placeholders. */
internal fun visibleChatProjectionMessages(
    messages: List<AgentChatMessageUi>,
    editTargetMessageId: String?,
): List<AgentChatMessageUi> {
    val edited = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, editTargetMessageId)
    val firstHidden = edited.indexOfFirst { it is AgentMessageUi && it.content.isBlank() }
    if (firstHidden < 0) return edited
    return buildList {
        edited.forEach { message ->
            if (message !is AgentMessageUi || message.content.isNotBlank()) add(message)
        }
    }
}

/**
 * Conservative synchronous fast path for a single streaming assistant's text-only tail update.
 *
 * Inputs must be immutable published UI snapshots (as required by the existing remember callers).
 * Without a runtime revision token we MUST check every prefix object: this is still O(n), but
 * skips terminal normalization, filtering allocations, regrouping and ID-set construction.
 * Append, deletion, edits, finalization, work changes and context boundaries use the original full
 * projection. A tail moved before a terminal notice by normalization also uses the full path.
 */
internal class AgentChatTimelineProjector(
    private val projectVisible: (List<AgentChatMessageUi>, String?) -> List<AgentChatMessageUi> =
        ::visibleChatProjectionMessages,
    private val projectEntries: (List<AgentChatMessageUi>) -> List<AgentTimelineEntry> =
        { it.toTimelineEntries() },
    private val projectMetadata: (List<AgentChatMessageUi>, List<AgentTimelineEntry>) -> AgentTimelineMetadata =
        ::timelineMetadata,
) {
    fun project(
        messages: List<AgentChatMessageUi>,
        nextContext: AgentChatProjectionContext,
        previous: AgentChatTimelineSnapshot? = null,
    ): AgentChatTimelineSnapshot {
        if (previous != null && messages === previous.sourceMessages && nextContext == previous.context) return previous
        return previous?.let { tryUpdateTail(messages, nextContext, it) } ?: run {
            val visible = projectVisible(messages, nextContext.editTargetMessageId)
            val entries = projectEntries(visible)
            AgentChatTimelineSnapshot(messages, nextContext, visible, entries, projectMetadata(visible, entries))
        }
    }

    private fun tryUpdateTail(
        messages: List<AgentChatMessageUi>,
        nextContext: AgentChatProjectionContext,
        previous: AgentChatTimelineSnapshot,
    ): AgentChatTimelineSnapshot? {
        if (nextContext != previous.context || !nextContext.allowsTailUpdate) return null
        val oldSource = previous.sourceMessages
        if (messages.isEmpty() || messages.size != oldSource.size) return null
        val before = oldSource.last() as? AgentMessageUi ?: return null
        val after = messages.last() as? AgentMessageUi ?: return null
        // Compare every non-text field through data-class equality (also future fields).
        if (!before.isStreaming || !after.isStreaming ||
            before.copy(content = after.content) != after ||
            before.content.isBlank() || after.content.isBlank()) return null
        if (previous.visibleMessages.lastOrNull() !== before ||
            (previous.entries.lastOrNull() as? AgentTimelineEntry.Message)?.message !== before) return null
        val oldPrefix = oldSource.iterator()
        val newPrefix = messages.iterator()
        repeat(messages.lastIndex) {
            val message = newPrefix.next()
            if (message !== oldPrefix.next()) return null
            // A repeated ID may acquire a different slot during terminal normalization.
            if (message.id == after.id) return null
        }
        return AgentChatTimelineSnapshot(
            sourceMessages = messages,
            context = nextContext,
            visibleMessages = TailSnapshotList(previous.visibleBase, after),
            entries = TailSnapshotList(previous.entriesBase, AgentTimelineEntry.Message(after)),
            metadata = previous.metadata,
            visibleBase = previous.visibleBase,
            entriesBase = previous.entriesBase,
        )
    }
}

/** Immutable view sharing only an unchanged prefix; previously returned snapshots stay intact. */
private class TailSnapshotList<T>(
    private val base: List<T>,
    private val tail: T,
) : AbstractList<T>(), RandomAccess {
    override val size: Int get() = base.size

    override fun get(index: Int): T {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index, size=$size")
        return if (index == size - 1) tail else base[index]
    }
}
