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
     * 按操作栏分段删除：删掉目标所在的一段以及它下面的所有段，上面的段原样保留。
     * 删最后一段时只去掉这一段。模型上下文在同一位置截断；该位置已被压缩时，
     * 保留压缩摘要，或用时间线标记上的摘要加之后的可见消息重建，而不是拒绝删除。
     */
    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val segments = actionBarSegments(state.messages)
        val segment = segments.firstOrNull { it.ownerId == targetMessageId } ?: return null
        val messages = state.messages.take(segment.start)
        val history = if (messages.isEmpty()) emptyList() else historyBefore(state, segment.start) ?: return null
        return state.copy(
            messages = messages,
            history = history,
            messageEdit = null,
            // A message bill belongs to an old request, not the newly truncated history.
            livePromptTokens = null,
            livePromptIsProjected = false,
            contextHasStarted = true,
            receiptPredictionTokens = if (!state.contextAwaitingReceipt && state.cloudRouteSignature != null &&
                state.livePromptTokens != null && !state.livePromptIsProjected &&
                state.cloudHistoryTokens != null && state.cloudRequestOverheadTokens != null)
                io.github.mangi.eta.ui.model.RequestOverheadCalibration.receiptEstimate(
                    state.livePromptTokens, state.cloudHistoryTokens, state.cloudRequestOverheadTokens,
                    history.sumOf { io.github.mangi.eta.agent.model.AgentContextBudget.countMessage(it) },
                    state.cloudRequestOverheadTokens) else null,
            cloudReceiptRequestId = null, contextReceiptEvidence = null,
            cloudHistoryTokens = null,
            cloudRequestOverheadTokens = null,
        )
    }

    /** 删除确认框用：目标段下面还会被一起删掉的段数。 */
    fun laterSegmentCount(state: AgentChatUiState, targetMessageId: String): Int? {
        val segments = actionBarSegments(state.messages)
        val index = segments.indexOfFirst { it.ownerId == targetMessageId }
        return if (index < 0) null else segments.size - 1 - index
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

    /** 与展示消息 [0, cut) 对应的模型上下文。 */
    private fun historyBefore(state: AgentChatUiState, cut: Int): List<AgentModelClient.ConversationMessage>? =
        tailHistoryBefore(state, cut) ?: exactHistoryBefore(state, cut) ?: compactedHistoryBefore(state, cut)

    /**
     * 从上下文末尾往回去掉被删的部分：被删的提问逐条对上，被删的回复按正文对上，
     * 中间的工具调用随之去掉。被删区域里有压缩标记或对不上时返回 null。
     * 当前轮次的提问已被压缩时也能用，且保留未删部分的工具记录。
     */
    private fun tailHistoryBefore(state: AgentChatUiState, cut: Int): List<AgentModelClient.ConversationMessage>? {
        val deleted = state.messages.subList(cut, state.messages.size)
        if (deleted.any { it is ContextCompactedMessageUi }) return null
        var usersLeft = deleted.count { it is UserMessageUi }
        val replies = deleted.filterIsInstance<AgentMessageUi>()
            .map { it.content.trim() }.filter { it.isNotEmpty() }.toSet()
        // 被删的段里没有提问也没有正文（例如只有一条停止提示），上下文不用动。
        if (usersLeft == 0 && replies.isEmpty()) return state.history
        var removedReplies = 0
        var end = state.history.size
        while (end > 0) {
            val message = state.history[end - 1]
            if (AgentContextCompactor.isCompressionSummary(message)) break
            if (message.role == "user" && isHiddenContinuePrompt(message)) {
                // 暂停后自动续写的隐藏提示，界面上没有对应气泡，跟着被删区域一起去掉。
            } else if (message.role == "user") {
                if (usersLeft == 0) break
                usersLeft--
            } else if (message.role == "assistant" && message.content.isNotBlank()) {
                if (message.content.trim() !in replies) break
                removedReplies++
            }
            end--
        }
        if (usersLeft != 0 || (replies.isNotEmpty() && removedReplies == 0)) return null
        // 保留的提问一条都不在上下文里、又没有摘要：不是压缩，不能当成可删。
        if (end == 0 && state.messages.take(cut).any { it is UserMessageUi }) return null
        // 保留下来的回复若发起过工具调用，结果一并保留，避免孤立的 tool_call。
        if (end > 0 && state.history[end - 1].role == "assistant" && state.history[end - 1].toolCallsJson.isNotBlank()) {
            while (end < state.history.size && state.history[end].role == "tool") end++
        }
        return state.history.take(end)
    }

    private fun isHiddenContinuePrompt(message: AgentModelClient.ConversationMessage): Boolean =
        AgentContextCompactor.isSteeringUserMessage(message) &&
            !message.content.trimStart().startsWith(AgentContextCompactor.STEERING_USER_PREFIX)

    private fun exactHistoryBefore(state: AgentChatUiState, cut: Int): List<AgentModelClient.ConversationMessage>? {
        if (cut < state.messages.size && state.messages[cut] is UserMessageUi) {
            return historyUserIndex(state, cut)?.let(state.history::take)
        }
        val anchor = (cut - 1 downTo 0).firstOrNull { state.messages[it] is UserMessageUi } ?: return null
        val anchorIndex = historyUserIndex(state, anchor) ?: return null
        val between = state.messages.subList(anchor + 1, cut)
        // 中途压缩过时，历史里这一段已不是逐条对应，交给摘要重建。
        if (between.any { it is ContextCompactedMessageUi }) return null
        val keepReplies = between.count { it is AgentMessageUi && it.content.isNotBlank() }
        var end = anchorIndex + 1
        var seen = 0
        while (seen < keepReplies && end < state.history.size) {
            val message = state.history[end]
            if (message.role == "user") return null
            end++
            if (message.role == "assistant" && message.content.isNotBlank()) seen++
        }
        if (seen < keepReplies) return null
        // 已保留回复发起的工具调用要带上结果，避免留下孤立的 tool_call。
        while (end < state.history.size && state.history[end].role == "tool") end++
        return state.history.take(end)
    }

    /**
     * 截断点已被压缩：取截断点之前最近一次压缩的摘要，再接上摘要之后到截断点的可见消息。
     * 该摘要就是当前上下文里的那份时，直接用原摘要；否则用时间线标记上保存的摘要正文。
     */
    private fun compactedHistoryBefore(state: AgentChatUiState, cut: Int): List<AgentModelClient.ConversationMessage>? {
        val markerIndex = (cut - 1 downTo 0).firstOrNull { index ->
            val message = state.messages[index]
            message is ContextCompactedMessageUi && message.compactedCount > 0 && message.summary.isNotBlank()
        }
        val currentSummaries = state.history.filter(AgentContextCompactor::isCompressionSummary)
        if (markerIndex == null) {
            // 没有时间线标记的旧会话：只有上下文里确有摘要时才视为压缩过，否则保持拒绝。
            if (currentSummaries.isEmpty()) return null
            return reconstructHistory(state.messages.take(cut))
        }
        val laterMarker = (markerIndex + 1 until state.messages.size).any { index ->
            val message = state.messages[index]
            message is ContextCompactedMessageUi && message.compactedCount > 0 && message.summary.isNotBlank()
        }
        val summary = if (!laterMarker && currentSummaries.isNotEmpty()) {
            currentSummaries
        } else {
            val marker = state.messages[markerIndex] as ContextCompactedMessageUi
            listOf(
                AgentModelClient.ConversationMessage(
                    role = "user",
                    content = "${AgentContextCompactor.SUMMARY_PREFIX_ZH}\n${marker.summary.trim()}",
                ),
            )
        }
        return summary + reconstructHistory(state.messages.subList(markerIndex + 1, cut))
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

