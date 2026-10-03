package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.question.*
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi

/** Pure projection; drafts belong to their exact conversation/run/call/question. */
internal object AgentQuestionProjection {
    fun sameOwner(a: AgentQuestionRequest, b: AgentQuestionRequest): Boolean =
        a.conversationId == b.conversationId && a.runId == b.runId &&
        a.toolCallId == b.toolCallId && a.questionId == b.questionId

    fun messageId(r: AgentQuestionRequest): String = "question-" +
        listOf(r.conversationId, r.runId, r.questionId, r.toolCallId).joinToString("") { "${it.length}:$it" }

    fun requested(conversationId: String, runId: String, request: AgentQuestionRequest,
        messages: List<AgentChatMessageUi>, acceptNew: Boolean = true, replaying: Boolean = false): List<AgentChatMessageUi> {
        if (request.conversationId != conversationId || request.runId != runId) return messages
        val index = messages.indexOfFirst { it is AgentQuestionMessageUi && sameOwner(it.request, request) }
        if (index >= 0) {
            val existing = messages[index] as AgentQuestionMessageUi
            // A confirmed live runtime replay can restore a locally interrupted draft.
            val restored = if (replaying && existing.status == AgentQuestionStatus.Interrupted)
                existing.copy(status = AgentQuestionStatus.Waiting, submitting = false, error = null) else existing
            return if (replaying) messages.filterIndexed { i, _ -> i != index } + restored else messages
        }
        return if (acceptNew) messages + AgentQuestionMessageUi(messageId(request), request) else messages
    }

    fun resolved(conversationId: String, runId: String, event: AgentEvent.QuestionResolved,
        messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        if (event.runId != runId || event.status == AgentQuestionStatus.Waiting) return messages
        val index = messages.indices.filter { i ->
            val m = messages[i] as? AgentQuestionMessageUi
            m != null && m.request.conversationId == conversationId && m.request.runId == runId &&
                m.request.questionId == event.questionId
        }.singleOrNull() ?: return messages
        val current = messages[index] as AgentQuestionMessageUi
        if (current.status == AgentQuestionStatus.Answered) return messages
        if (event.status == AgentQuestionStatus.Answered &&
            (event.answer == null || !AgentQuestionCodec.validateAnswer(current.request, event.answer).accepted)) return messages
        return messages.mapIndexed { i, m -> if (i == index) current.copy(status = event.status,
            answer = event.answer.takeIf { event.status == AgentQuestionStatus.Answered },
            submitting = false, error = null) else m }
    }

    fun interruptWaiting(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        messages.map { m -> if (m is AgentQuestionMessageUi && m.request.runId == runId &&
            m.status == AgentQuestionStatus.Waiting) m.copy(status = AgentQuestionStatus.Interrupted,
                submitting = false, error = null) else m }

    fun hasWaiting(messages: List<AgentChatMessageUi>): Boolean = messages.any {
        it is AgentQuestionMessageUi && it.status == AgentQuestionStatus.Waiting
    }

    fun draftAnswer(m: AgentQuestionMessageUi): AgentQuestionAnswer = AgentQuestionAnswer(m.answerKind,
        m.selectedOptionId.takeIf { m.answerKind == "option" },
        if (m.answerKind == "other") m.otherText else "", if (m.request.allowNote) m.note else "")

    fun acknowledged(current: AgentQuestionMessageUi, answer: AgentQuestionAnswer,
        receipt: AgentQuestionReceipt): AgentQuestionMessageUi {
        if (current.status == AgentQuestionStatus.Answered) return current.copy(submitting = false, error = null)
        if (!receipt.accepted) {
            val ended = receipt.code in setOf("QUESTION_RUN_NOT_ACTIVE", "QUESTION_NOT_PENDING", "QUESTION_LATE")
            return current.copy(submitting = false,
                status = if (ended) AgentQuestionStatus.Interrupted else current.status,
                error = receipt.message.ifBlank { receipt.code })
        }
        return current.copy(status = AgentQuestionStatus.Answered, answer = answer, submitting = false, error = null)
    }
}
