package io.github.mangi.eta.ui.model

/** Frozen f282d1f6 implementation: test oracle only, never used by production. */
internal object LegacyTerminalMessageOrderReference {
    fun normalize(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        normalize(runId, messages, messages.knownTerminalRunIds())

    private fun normalize(
        runId: String,
        messages: List<AgentChatMessageUi>,
        knownRunIds: Set<String>,
    ): List<AgentChatMessageUi> {
        if (runId.isBlank() || runId !in knownRunIds) return messages
        val owners = knownRunIds + runId
        fun owns(message: AgentChatMessageUi): Boolean = message.ownerAmong(owners) == runId
        val terminalIndex = messages.indexOfFirst { it is SystemNoticeMessageUi && it.code.isTerminal() && owns(it) }
        if (terminalIndex < 0) return messages
        val body = linkedMapOf<String, AgentChatMessageUi>()
        val firstBodyIndices = mutableMapOf<String, Int>()
        var latestNotice = messages[terminalIndex] as SystemNoticeMessageUi
        messages.forEachIndexed { index, message ->
            when {
                message is SystemNoticeMessageUi && message.code.isTerminal() && owns(message) -> latestNotice = message
                message.isRunBody() && owns(message) -> {
                    firstBodyIndices.putIfAbsent(message.id, index)
                    body[message.id] = message
                }
            }
        }
        val firstNotice = messages[terminalIndex] as SystemNoticeMessageUi
        val ordered = buildList {
            messages.forEachIndexed { index, message ->
                when {
                    index == terminalIndex -> {
                        body.forEach { (id, value) ->
                            if (firstBodyIndices.getValue(id) > terminalIndex) add(value)
                        }
                        add(latestNotice.copy(id = firstNotice.id))
                    }
                    message is SystemNoticeMessageUi && message.code.isTerminal() && owns(message) -> Unit
                    message.isRunBody() && owns(message) -> {
                        if (firstBodyIndices[message.id] == index && index < terminalIndex) add(body.getValue(message.id))
                    }
                    else -> add(message)
                }
            }
        }
        return if (ordered == messages) messages else ordered
    }

    fun orderAll(messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        val owners = messages.knownTerminalRunIds()
        if (owners.isEmpty()) return messages
        val terminalRuns = messages.filterIsInstance<SystemNoticeMessageUi>()
            .filter { it.code.isTerminal() }.mapNotNull { it.ownerAmong(owners) }.distinct()
        return terminalRuns.fold(messages) { ordered, runId -> normalize(runId, ordered, owners) }
    }

    private fun List<AgentChatMessageUi>.knownTerminalRunIds(): Set<String> = buildSet {
        this@knownTerminalRunIds.forEach { message ->
            when (message) {
                is ThinkingMessageUi -> THINKING_ID.matchEntire(message.id)?.groupValues?.get(1)?.let { add(it) }
                is ToolActivityMessageUi -> TOOL_ID.matchEntire(message.id)?.groupValues?.get(1)?.let { add(it) }
                is UserMessageUi -> if (message.id.startsWith("user-") && !message.isSteerSupplement()) add(message.id.removePrefix("user-"))
                is SystemNoticeMessageUi -> when {
                    message.id.startsWith("interrupted-") -> add(message.id.removePrefix("interrupted-"))
                    message.id.startsWith("virtual-completed-") -> add(message.id.removePrefix("virtual-completed-"))
                }
                else -> Unit
            }
        }
        remove("")
    }

    private fun SystemNoticeCode.isTerminal(): Boolean = this != SystemNoticeCode.ModelRetry
    private fun AgentChatMessageUi.isRunBody(): Boolean = this is AgentMessageUi || this is ThinkingMessageUi || this is ToolActivityMessageUi
    private fun AgentChatMessageUi.ownerAmong(owners: Set<String>): String? = when (this) {
        is AgentMessageUi -> id.assistantOwnerAmong(owners)
        is SystemNoticeMessageUi -> when {
            !code.isTerminal() -> null
            id.startsWith("interrupted-") -> id.removePrefix("interrupted-").takeIf { it in owners }
            id.startsWith("virtual-completed-") -> id.removePrefix("virtual-completed-").takeIf { it in owners }
            else -> id.assistantOwnerAmong(owners)
        }
        is ThinkingMessageUi -> owners.filter { owner ->
            id.removePrefix("$owner-thinking-").takeIf { it != id }?.let(THINKING_SUFFIX::matches) == true
        }.maxByOrNull(String::length)
        is ToolActivityMessageUi -> owners.filter { owner ->
            id.removePrefix("$owner-tool-").takeIf { it != id }?.let(TOOL_SUFFIX::matches) == true
        }.maxByOrNull(String::length)
        else -> null
    }
    private fun String.assistantOwnerAmong(owners: Set<String>): String? =
        owners.filter { owner ->
            this == "assistant-$owner" || removePrefix("assistant-$owner-")
                .takeIf { it != this }?.let(ASSISTANT_SUFFIX::matches) == true
        }.maxByOrNull(String::length)

    private val ASSISTANT_SUFFIX = Regex("[0-9]+(?:-(?:[0-9]+|result|usage))?")
    private val THINKING_SUFFIX = Regex("[0-9]+(?:-(?:[0-9]+|fallback))?")
    private val TOOL_SUFFIX = Regex("[0-9]+-.+")
    private val THINKING_ID = Regex("(.+)-thinking-[0-9]+(?:-(?:[0-9]+|fallback))?")
    private val TOOL_ID = Regex("(.+?)-tool-[0-9]+-.+")
}
