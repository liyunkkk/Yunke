package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.MentionedConversation
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File

internal data class ConversationMentionQuery(
    val start: Int,
    val query: String,
)

internal object ConversationMention {
    const val MAX_TRANSCRIPT_CHARS = 240_000
    /** Snapshot file cap. The prompt itself does not carry this text. */
    const val SNAPSHOT_MAX_CHARS = 1_500_000
    const val MAX_RESULTS = 8
    const val MAX_ATTACHED = 3
    const val MAX_TOTAL_CHARS = 480_000

    fun queryAtCursor(text: String, cursor: Int): ConversationMentionQuery? {
        val index = cursor.coerceIn(0, text.length)
        val before = text.substring(0, index)
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && (before[at - 1] in 'a'..'z' || before[at - 1] in 'A'..'Z' ||
            before[at - 1].isDigit() || before[at - 1] in "._%+-/@")) return null
        val query = before.substring(at + 1)
        if (query.any { it == '\n' }) return null
        return ConversationMentionQuery(start = at, query = query)
    }

    fun candidates(
        conversations: List<ConversationSummaryUi>,
        query: String,
        excludeId: String?,
        alreadyAttached: Set<String>,
        limit: Int = MAX_RESULTS,
    ): List<ConversationSummaryUi> {
        val needle = query.trim()
        return conversations.asSequence()
            .filter { it.id != excludeId && it.id !in alreadyAttached }
            .filter { needle.isEmpty() || matches(it, needle) }
            .sortedByDescending { it.updatedAtMillis }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun matches(conversation: ConversationSummaryUi, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return conversation.title.contains(needle, ignoreCase = true) ||
            conversation.preview.contains(needle, ignoreCase = true)
    }

    const val OMISSION_MARKER = "\n\n[已截取：中间记录已省略，保留开头与最近记录]\n\n"

    fun transcript(
        messages: List<AgentChatMessageUi>,
        maxChars: Int = MAX_TRANSCRIPT_CHARS,
        filesDir: File? = null,
        conversationId: String? = null,
        toolEvidence: ConversationToolEvidence? = null,
    ): String {
        if (maxChars <= 0) return ""
        val toolDetailsDirectory = filesDir?.let { prepareToolDetailsDirectory(it, conversationId) }
        val chunks = messages.mapNotNull { formatMessage(it, toolDetailsDirectory, toolEvidence) }
        if (chunks.isEmpty()) return ""
        val joined = chunks.joinToString("\n\n")
        if (joined.length <= maxChars) return joined
        val keep = (maxChars - OMISSION_MARKER.length).coerceAtLeast(0)
        if (keep == 0) return OMISSION_MARKER.trim().take(maxChars)
        val headBudget = (keep / 2).coerceAtLeast(1)
        val tailBudget = (keep - headBudget).coerceAtLeast(1)
        return (joined.take(headBudget) + OMISSION_MARKER + joined.takeLast(tailBudget)).take(maxChars)
    }

    internal const val TOOL_DETAILS_DIRECTORY = "快照缓存/tools"

    private fun prepareToolDetailsDirectory(filesDir: File, conversationId: String?): File? {
        val token = sanitizeFileToken(conversationId.orEmpty().ifBlank { "conversation" })
        val directory = File(TerminalPrivateStorage.workspace(filesDir), "$TOOL_DETAILS_DIRECTORY/$token/${java.util.UUID.randomUUID()}")
        return runCatching { directory.mkdirs(); directory.takeIf { it.isDirectory } }.getOrNull()
    }

    internal fun toolActivityDetails(message: ToolActivityMessageUi): String = buildString {
        append("Evidence: summary only / 仅有摘要；原始参数与结果未在此文件中恢复。\n")
        message.argumentsSummary.trim().takeIf { it.isNotEmpty() }?.let {
            append("Arguments summary:\n").append(it).append('\n')
        }
        message.command?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("Command:\n").append(it).append('\n')
        }
        message.resultSummary?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("Result summary:\n").append(it).append('\n')
        }
    }.trim()

    private fun writeToolDetailsFile(directory: File, message: ToolActivityMessageUi, details: String): File? {
        val file = File(directory, "${sanitizeFileToken(message.id)}.txt")
        return runCatching {
            file.writeText(details)
            file.takeIf { it.isFile }
        }.getOrNull()
    }

    private fun sanitizeFileToken(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "tool" }

    private fun formatToolActivity(
        message: ToolActivityMessageUi,
        toolDetailsDirectory: File?,
        toolEvidence: ConversationToolEvidence?,
    ): String = buildString {
        append("Tool ${message.toolName}: ${message.status.name}")
        val original = toolEvidence?.original(message.id)
        val hasSummary = message.argumentsSummary.isNotBlank() || !message.command.isNullOrBlank() || !message.resultSummary.isNullOrBlank()
        val details = original?.details() ?: if (hasSummary) toolActivityDetails(message) else ""
        if (original != null) append("\nEvidence: stored tool response; tool-side truncation may still apply.")
        else if (hasSummary) append("\nEvidence: summary only / 仅有摘要（原文缺失、未保存、匹配不唯一或未在有界存档查询中找到）。")
        val detailsFile = if (details.isNotEmpty() && toolDetailsDirectory != null) {
            writeToolDetailsFile(toolDetailsDirectory, message, details)
        } else {
            null
        }
        if (detailsFile != null) {
            append("\nDetails file: ").append(detailsFile.absolutePath)
            append("\nBytes: ").append(detailsFile.length())
            append("\nUse read_file(path, offset_bytes=0, max_bytes=16384); advance by returned byte range until EOF. Do not infer completeness from the summary.")
        } else if (details.isNotEmpty()) {
            if (original != null) append("\n原文文件未能导出；以下仅显示摘要，不包含完整原文。")
            append('\n').append(toolActivityDetails(message))
        }
        if (message.imageCount > 0) {
            append("\nImages: ").append(message.imageCount)
        }
    }

    fun remainingTranscriptBudget(already: List<PendingConversationMentionUi>): Int =
        (MAX_TOTAL_CHARS - already.sumOf { it.transcript.length }).coerceAtLeast(0)

    /** Full snapshot for on-demand reads. Returns null when the directory cannot be created. */
    fun writeSnapshot(filesDir: File, conversationId: String, transcript: String): File? {
        if (transcript.isBlank()) return null
        val token = sanitizeFileToken(conversationId.ifBlank { "conversation" })
        val directory = File(TerminalPrivateStorage.workspace(filesDir), "$TOOL_DETAILS_DIRECTORY/$token")
        if (!runCatching { directory.mkdirs(); directory.isDirectory }.getOrDefault(false)) return null
        val file = File(directory, "snapshot.txt")
        return runCatching {
            file.writeText(transcript)
            file.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
    }

    private fun formatMessage(message: AgentChatMessageUi, toolDetailsDirectory: File?, toolEvidence: ConversationToolEvidence?): String? {
        return when (message) {
        is UserMessageUi -> {
            if (message.isResumeAfterCompress()) return null
            val parsed = AgentFileReferencePromptCodec.parse(message.content)
            buildString {
                if (parsed.conversations.isNotEmpty()) {
                    append("User mentioned conversations: ")
                    append(parsed.conversations.joinToString { it.title })
                    append('\n')
                }
                if (parsed.references.isNotEmpty()) {
                    append("User attached files: ")
                    append(parsed.references.joinToString { it.displayName })
                    append('\n')
                }
                val body = parsed.request.trim()
                if (body.isEmpty() && parsed.conversations.isEmpty() && parsed.references.isEmpty()) return null
                append("User: ")
                append(body.ifBlank { "(attachment only)" })
            }
        }
        is AgentMessageUi -> message.content.trim().takeIf { it.isNotEmpty() }?.let { "Assistant: $it" }
        is ThinkingMessageUi -> message.content.trim().takeIf { it.isNotEmpty() }?.let { "Thinking: $it" }
        is ToolSummaryMessageUi -> message.tools.takeIf { it.isNotEmpty() }?.let { "Tools: ${it.joinToString()}" }
        is ToolActivityMessageUi -> formatToolActivity(message, toolDetailsDirectory, toolEvidence)
        is ContextCompactedMessageUi -> {
            val summary = message.summary.trim()
            if (summary.isEmpty()) "Context compressed (${message.compactedCount} messages)"
            else "Context compressed (${message.compactedCount} messages): $summary"
        }
        is SystemNoticeMessageUi -> "系统状态：${message.code.name}"
        is RunTraceMessageUi, is SuggestionChipsMessageUi -> null
    }
    }
}

internal fun List<PendingConversationMentionUi>.toMentionedConversations(): List<MentionedConversation> =
    map {
        MentionedConversation(
            id = it.conversationId,
            title = it.title,
            transcript = "",
            snapshotPath = it.snapshotPath,
        )
    }

/** One composer-scoped controller, passed explicitly through both Home and Chat screens. */
internal data class ConversationMentionInputUi(
    val conversations: List<ConversationSummaryUi> = emptyList(),
    val currentConversationId: String? = null,
    val pending: List<PendingConversationMentionUi> = emptyList(),
    val onAttach: (String) -> Boolean = { false },
    val onRemove: (String) -> Unit = {},
)
