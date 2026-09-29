package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.latestBilledContextTokens
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement

/** 以用户轮次为边界同步裁剪展示消息与模型上下文。 */
internal object AgentConversationRevisionReducer {
    data class Boundary(
        val userMessage: UserMessageUi,
        val userMessageIndex: Int,
        val historyPrefix: List<AgentModelClient.ConversationMessage>,
        val laterTurnCount: Int,
        val contextWasCompacted: Boolean,
    )

    data class BranchPrefix(
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
    )

    fun boundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val userMessage = state.messages[userMessageIndex] as UserMessageUi
        val historyIndex = historyUserIndex(state, userMessageIndex)
        val laterUsers = state.messages.drop(userMessageIndex + 1).any { it is UserMessageUi }
        if (historyIndex == null && userMessage.isSteerSupplement()) {
            // 停止时尚未写进历史的最后一条追加：它之后没有任何内容可被抹掉，
            // 以完整历史为前缀替换它是安全的；其它缺失的追加仍拒绝，避免误认成原问题。
            val owner = ownerRunId(userMessage.id)
            if (laterUsers || state.history.none { it.turnId == owner }) return null
            return Boundary(
                userMessage = userMessage,
                userMessageIndex = userMessageIndex,
                historyPrefix = state.history,
                laterTurnCount = 0,
                contextWasCompacted = false,
            )
        }
        val laterTurnCount = state.messages.drop(userMessageIndex + 1).count {
            it is UserMessageUi && !it.isSteerSupplement()
        }
        val compacted = historyIndex == null && wasRemovedByCompaction(state, userMessageIndex)
        // A missing match is not proof of compaction. Fail closed instead of erasing history.
        if (historyIndex == null && !compacted) return null

        return Boundary(
            userMessage = userMessage,
            userMessageIndex = userMessageIndex,
            historyPrefix = historyIndex?.let(state.history::take).orEmpty(),
            laterTurnCount = laterTurnCount,
            contextWasCompacted = compacted,
        )
    }

    /**
     * 操作栏只删它和上一条操作栏之间的消息。用户气泡自己的栏不带走回复，
     * 回复栏也不回头删掉提问或更早一段。
     */
    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val segments = actionBarSegments(state.messages)
        val segment = segments.firstOrNull { it.ownerId == targetMessageId } ?: return null
        val history = historyWithoutSegment(state, segment, segments) ?: return null
        val messages = state.messages.filterIndexed { index, _ ->
            index < segment.start || index > segment.endInclusive
        }
        return state.copy(
            messages = messages,
            history = history,
            messageEdit = null,
            livePromptTokens = latestBilledContextTokens(messages),
            livePromptIsProjected = false,
            cloudHistoryTokens = null,
            cloudRequestOverheadTokens = null,
        )
    }

    internal data class ActionBarSegment(val start: Int, val endInclusive: Int, val ownerId: String)

    /** 与时间线上的操作栏同一套分界：用户气泡单独一段，回复在下一条用户消息或下一次收口处分段。 */
    internal fun actionBarSegments(messages: List<AgentChatMessageUi>): List<ActionBarSegment> {
        val segments = mutableListOf<ActionBarSegment>()
        var start = 0
        var owner: AgentChatMessageUi? = null
        var terminalIndex = -1
        fun flush(end: Int) {
            val current = owner
            if (current != null && end >= start) {
                segments += ActionBarSegment(start, end, current.id)
            }
            start = end + 1
            owner = null
            terminalIndex = -1
        }
        messages.forEachIndexed { index, message ->
            when (message) {
                is UserMessageUi -> {
                    if (index > start) flush(index - 1)
                    segments += ActionBarSegment(index, index, message.id)
                    start = index + 1
                }
                is AgentMessageUi -> {
                    if (message.content.isNotBlank()) {
                        if (terminalIndex >= 0) flush(terminalIndex)
                        owner = message
                    }
                }
                is SystemNoticeMessageUi -> {
                    if (message.code == SystemNoticeCode.ModelRetry) {
                        if (terminalIndex >= 0) flush(terminalIndex)
                    } else {
                        if (message.code != SystemNoticeCode.Completed || owner == null) owner = message
                        terminalIndex = index
                    }
                }
                else -> Unit
            }
        }
        if (owner != null) flush(messages.lastIndex)
        return segments
    }

    private fun historyWithoutSegment(
        state: AgentChatUiState,
        segment: ActionBarSegment,
        segments: List<ActionBarSegment>,
    ): List<AgentModelClient.ConversationMessage>? {
        val message = state.messages[segment.start]
        if (segment.start == segment.endInclusive && message is UserMessageUi) {
            val index = historyUserIndex(state, segment.start) ?: return null
            return state.history.filterIndexed { historyIndex, _ -> historyIndex != index }
        }
        val previousUser = (segment.start - 1 downTo 0).firstOrNull { index ->
            val candidate = state.messages[index]
            candidate is UserMessageUi && !candidate.isSteerSupplement()
        }
        val nextUser = (segment.endInclusive + 1 until state.messages.size).firstOrNull { index ->
            val candidate = state.messages[index]
            candidate is UserMessageUi && !candidate.isSteerSupplement()
        }
        val historyFrom = previousUser?.let { historyUserIndex(state, it)?.plus(1) ?: return null } ?: 0
        val historyTo = nextUser?.let { historyUserIndex(state, it) ?: return null } ?: state.history.size
        if (historyFrom > historyTo || historyFrom > state.history.size) return null
        val replySegments = segments.filter { candidate ->
            candidate.start >= (previousUser?.plus(1) ?: 0) &&
                (nextUser == null || candidate.endInclusive < nextUser) &&
                state.messages[candidate.start] !is UserMessageUi
        }
        if (replySegments.size <= 1) {
            return state.history.filterIndexed { index, _ -> index !in historyFrom until historyTo }
        }
        val drop = mutableSetOf<Int>()
        val buckets = replySegments.associateWith { mutableListOf<Int>() }
        var bucket = 0
        for (offset in 0 until (historyTo - historyFrom)) {
            val role = state.history[historyFrom + offset].role
            if (role != "assistant" && role != "tool") continue
            val target = replySegments[bucket.coerceAtMost(replySegments.lastIndex)]
            buckets.getValue(target) += historyFrom + offset
            val needed = state.messages.subList(target.start, target.endInclusive + 1).count { item ->
                (item is AgentMessageUi && item.content.isNotBlank()) || item is ToolActivityMessageUi
            }.coerceAtLeast(1)
            if (bucket < replySegments.lastIndex && buckets.getValue(target).size >= needed) bucket++
        }
        buckets[segment]?.let(drop::addAll)
        return state.history.filterIndexed { index, _ -> index !in drop }
    }

    /**
     * 从目标消息分出一条独立会话：保留该消息及之前的展示内容，
     * 模型上下文截到同一轮结束（助手消息含本轮回复；用户消息只含该条提问）。
     */
    fun branchPrefix(state: AgentChatUiState, targetMessageId: String): BranchPrefix? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val historyUserIndex = historyUserIndex(state, userMessageIndex)
        if (historyUserIndex == null &&
            ((state.messages[userMessageIndex] as UserMessageUi).isSteerSupplement() ||
                !wasRemovedByCompaction(state, userMessageIndex))) return null
        val messages = state.messages.take(targetIndex + 1)
        val history = if (historyUserIndex == null) {
            reconstructHistory(messages)
        } else if (state.messages[targetIndex] is UserMessageUi) {
            state.history.take(historyUserIndex + 1)
        } else {
            // A branch must not include later supplements that are not visible in its prefix.
            val nextUser = (historyUserIndex + 1 until state.history.size).firstOrNull {
                state.history[it].role == "user"
            }
            if (nextUser != null) state.history.take(nextUser) else state.history
        }
        return BranchPrefix(messages = messages, history = history)
    }


    /** Branch copies prefix message ids with the new conversation id. The run id stays after the last colon. */
    private fun ownerRunId(userMessageId: String): String =
        userMessageId.substringAfterLast(':').removePrefix("user-").substringBefore("-supplement-")

    /** A branch rewrites cache paths in the bubble but not always in the stored model history. */
    private fun revisionComparableText(text: String): String =
        text.replace(Regex("/eta-chat-images/conv-[^/]+/"), "/eta-chat-images/conv/")

    /** Stable owner + exact user payload; list length is never evidence of message identity. */
    private fun historyUserIndex(state: AgentChatUiState, uiIndex: Int): Int? {
        val user = state.messages[uiIndex] as UserMessageUi
        val runId = ownerRunId(user.id)
        val expected = revisionComparableText(user.content.trim())
        val steering = revisionComparableText(
            io.github.mangi.eta.agent.model.AgentContextCompactor.steeringUserContent(user.content).trim(),
        )
        fun matches(message: AgentModelClient.ConversationMessage): Boolean {
            if (message.role != "user" || AgentContextCompactor.isCompressionSummary(message)) return false
            val text = revisionComparableText(historyText(message))
            if (text == expected || text == steering) return true
            // Attachment envelopes differ between UI/persisted/vision requests. Only normalize
            // inside the same proven owner turn, never across repeated questions or supplements.
            if (message.turnId != runId || user.isSteerSupplement()) return false
            val parsed = AgentFileReferencePromptCodec.parse(text)
            val ui = AgentFileReferencePromptCodec.parse(expected)
            return ui.request.isNotBlank() && parsed.request.trim() == ui.request.trim() &&
                parsed.conversations == ui.conversations
        }
        val candidates = state.history.indices.filter { matches(state.history[it]) }
        val inTurn = candidates.filter { state.history[it].turnId == runId }
        val scoped = inTurn.ifEmpty { candidates }
        // Disambiguate only identical payloads, not every user bubble including supplements.
        val laterDuplicates = state.messages.drop(uiIndex + 1).filterIsInstance<UserMessageUi>().count {
            revisionComparableText(it.content.trim()) == expected &&
                (inTurn.isEmpty() || ownerRunId(it.id) == runId)
        }
        return scoped.getOrNull(scoped.size - 1 - laterDuplicates)
    }

    private fun historyText(message: AgentModelClient.ConversationMessage): String =
        message.content.ifBlank {
            runCatching {
                val parts = org.json.JSONArray(message.contentJson)
                (0 until parts.length()).mapNotNull { index ->
                    parts.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.filter { it.isNotBlank() }.joinToString("\n")
            }.getOrDefault("")
        }.trim()

    /** Evidence must place this specific missing message before a real summary boundary.
     * A tool-pruning marker or a summary elsewhere in the conversation is insufficient. */
    private fun wasRemovedByCompaction(state: AgentChatUiState, uiIndex: Int): Boolean {
        val user = state.messages[uiIndex] as UserMessageUi
        val owner = ownerRunId(user.id)
        if (state.history.any { it.role == "user" && it.turnId == owner }) return false
        val markers = state.messages.withIndex().filter { (_, message) ->
            message is ContextCompactedMessageUi && message.compactedCount > 0 && message.summary.isNotBlank()
        }
        if (markers.isNotEmpty()) return markers.any { it.index > uiIndex }
        // Legacy conversations may lack UI markers. Require a real summary plus a retained,
        // exactly matched later user turn; do not infer from a smaller history list alone.
        val summaryIndex = state.history.indexOfLast(AgentContextCompactor::isCompressionSummary)
        if (summaryIndex < 0) return false
        return (uiIndex + 1 until state.messages.size).any { later ->
            state.messages[later] is UserMessageUi &&
                historyUserIndex(state, later)?.let { it > summaryIndex } == true
        }
    }

    fun outboundHistory(state: AgentChatUiState): List<AgentModelClient.ConversationMessage> {
        val targetId = state.messageEdit?.targetMessageId ?: return state.history
        return boundary(state, targetId)?.historyPrefix ?: state.history
    }

    fun visibleMessagesForEdit(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> {
        if (targetMessageId == null) return messages
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        return if (targetIndex < 0) messages else messages.take(targetIndex + 1)
    }

    private fun reconstructHistory(
        messages: List<AgentChatMessageUi>,
    ): List<AgentModelClient.ConversationMessage> = messages.mapNotNull { message ->
        when (message) {
            is UserMessageUi -> AgentModelClient.ConversationMessage(
                role = "user",
                content = message.content,
            )
            is AgentMessageUi -> message.content.takeIf { it.isNotBlank() }?.let { content ->
                AgentModelClient.ConversationMessage(role = "assistant", content = content)
            }
            else -> null
        }
    }

    /**
     * 暂停/结束任务后，屏幕上已写出的助手正文必须进模型历史。
     * 否则下一轮请求看不到刚才的完整回答。
     */
    fun commitVisibleAssistantIntoHistory(
        history: List<AgentModelClient.ConversationMessage>,
        messages: List<AgentChatMessageUi>,
    ): List<AgentModelClient.ConversationMessage> {
        val lastUserIndex = messages.indexOfLast { message ->
            message is UserMessageUi
        }
        val partial = messages
            .drop((lastUserIndex + 1).coerceAtLeast(0))
            .filterIsInstance<AgentMessageUi>()
            .lastOrNull { it.content.isNotBlank() }
            ?: return history
        return historyWithTrailingPartial(history, partial)
    }

    fun historyWithTrailingPartial(
        history: List<AgentModelClient.ConversationMessage>,
        partial: AgentMessageUi,
    ): List<AgentModelClient.ConversationMessage> {
        val migrated = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(history)
        val partialMessage = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = partial.content,
            turnId = migrated.lastOrNull { it.turnId.isNotBlank() }?.turnId.orEmpty(),
        )
        val last = migrated.lastOrNull()
        if (last?.role == "assistant" && last.content == partial.content) return migrated
        val extendsTrailingText = last?.role == "assistant" &&
            last.toolCallsJson.isBlank() && last.content.isNotBlank() && partial.content.startsWith(last.content)
        return if (extendsTrailingText) migrated.dropLast(1) + partialMessage else migrated + partialMessage
    }

}

internal fun AgentChatMessageUi.withId(id: String): AgentChatMessageUi = when (this) {
    is UserMessageUi -> copy(id = id)
    is AgentMessageUi -> copy(id = id)
    is SystemNoticeMessageUi -> copy(id = id)
    is ThinkingMessageUi -> copy(id = id)
    is RunTraceMessageUi -> copy(id = id)
    is ToolSummaryMessageUi -> copy(id = id)
    is ContextCompactedMessageUi -> copy(id = id)
    is ToolActivityMessageUi -> copy(id = id)
    is SuggestionChipsMessageUi -> copy(id = id)
}

internal fun AgentChatMessageUi.rewritePaths(rewrite: (String) -> String): AgentChatMessageUi = when (this) {
    is UserMessageUi -> copy(
        content = rewrite(content),
        imageSources = imageSources.map(rewrite),
    )
    is AgentMessageUi -> copy(content = rewrite(content))
    is ThinkingMessageUi -> copy(content = rewrite(content))
    is ToolActivityMessageUi -> copy(
        argumentsSummary = rewrite(argumentsSummary),
        command = command?.let(rewrite),
        resultSummary = resultSummary?.let(rewrite),
    )
    else -> this
}

internal fun AgentModelClient.ConversationMessage.rewritePaths(
    rewrite: (String) -> String,
): AgentModelClient.ConversationMessage = copy(
    content = rewrite(content),
    contentJson = rewrite(contentJson),
)

