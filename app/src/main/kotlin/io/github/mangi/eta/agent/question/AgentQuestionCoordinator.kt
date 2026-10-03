package io.github.mangi.eta.agent.question

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 主代理一次只等待一个 ask_user。四个身份字段在锁内一次性匹配并校验；
 * 取消通过 [AgentRunController.register] 唤醒，等待本身没有超时。
 * [AgentEvent.QuestionRequested] 在 pending 和取消绑定都安装后才发出。
 * 受理在锁内完成；[AgentEvent.QuestionResolved] 在锁外发布，最后才 signal，
 * 避免等待方先进入下一轮。返回 null 只表示取消或中断，绝不会伪装成已回答。
 */
internal class AgentQuestionCoordinator(
    private val controller: AgentRunController,
    private val onEvent: (AgentEvent) -> Unit,
) {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private var pending: Slot? = null

    fun awaitAnswer(request: AgentQuestionRequest): AgentQuestionAnswer? {
        lock.withLock {
            if (pending?.status == AgentQuestionStatus.Waiting) {
                throw IllegalStateException("已有待回答问题")
            }
            pending = Slot(request, AgentQuestionStatus.Waiting)
        }
        val binding = controller.register {
            lock.withLock {
                val current = pending ?: return@withLock
                if (current.status == AgentQuestionStatus.Waiting && sameRequest(current.request, request)) {
                    pending = current.copy(status = AgentQuestionStatus.Cancelled)
                    arrived.signalAll()
                }
            }
        }
        try {
            try {
                onEvent(AgentEvent.QuestionRequested(request))
            } catch (failure: Throwable) {
                lock.withLock {
                    val current = pending
                    if (current != null && sameRequest(current.request, request) &&
                        current.status == AgentQuestionStatus.Waiting
                    ) {
                        pending = current.copy(status = AgentQuestionStatus.Interrupted)
                    }
                }
                throw failure
            }
            val slot = try {
                lock.withLock {
                    var current = pending
                    while (current != null && sameRequest(current.request, request) &&
                        current.status == AgentQuestionStatus.Waiting
                    ) {
                        if (controller.isCancelled) {
                            val cancelled = current.copy(status = AgentQuestionStatus.Cancelled)
                            pending = cancelled
                            current = cancelled
                            break
                        }
                        arrived.await()
                        current = pending
                    }
                    current?.takeIf { sameRequest(it.request, request) }
                }
            } catch (interrupted: InterruptedException) {
                val interruptedSlot = lock.withLock {
                    val current = pending
                    if (current != null && sameRequest(current.request, request) &&
                        current.status == AgentQuestionStatus.Waiting
                    ) {
                        val updated = current.copy(status = AgentQuestionStatus.Interrupted)
                        pending = updated
                        updated
                    } else {
                        current?.takeIf { sameRequest(it.request, request) }
                    }
                }
                Thread.currentThread().interrupt()
                interruptedSlot
            }
            if (slot == null || slot.status == AgentQuestionStatus.Answered) {
                return slot?.answer
            }
            onEvent(
                AgentEvent.QuestionResolved(
                    questionId = slot.request.questionId,
                    runId = slot.request.runId,
                    status = slot.status,
                    answer = null,
                ),
            )
            return null
        } finally {
            binding.close()
        }
    }

    fun submitAnswer(
        conversationId: String,
        runId: String,
        questionId: String,
        toolCallId: String,
        answer: AgentQuestionAnswer,
    ): AgentQuestionReceipt {
        var wake = false
        val decision = lock.withLock {
            if (conversationId.isBlank() || runId.isBlank() || questionId.isBlank() || toolCallId.isBlank()) {
                return@withLock Decision(foreign(), null)
            }
            val current = pending ?: return@withLock Decision(notPending(), null)
            val matches = sameIds(current.request, conversationId, runId, questionId, toolCallId)
            when (current.status) {
                AgentQuestionStatus.Waiting -> if (!matches) {
                    Decision(foreign(), null)
                } else if (controller.isCancelled) {
                    Decision(AgentQuestionReceipt(false, "QUESTION_LATE", "问题已取消，不能再回答"), null)
                } else {
                    val validation = AgentQuestionCodec.validateAnswer(current.request, answer)
                    if (!validation.accepted) {
                        Decision(validation, null)
                    } else {
                        pending = current.copy(status = AgentQuestionStatus.Answered, answer = answer)
                        wake = true
                        Decision(validation, resolved(current.request, AgentQuestionStatus.Answered, answer))
                    }
                }
                AgentQuestionStatus.Answered -> if (matches) {
                    Decision(AgentQuestionReceipt(false, "QUESTION_DUPLICATE", "该问题已回答"), null)
                } else {
                    Decision(foreign(), null)
                }
                AgentQuestionStatus.Cancelled, AgentQuestionStatus.Interrupted -> if (matches) {
                    Decision(AgentQuestionReceipt(false, "QUESTION_LATE", "问题已结束，不能再回答"), null)
                } else {
                    Decision(foreign(), null)
                }
            }
        }
        try {
            decision.event?.let(onEvent)
        } finally {
            if (wake) lock.withLock { arrived.signalAll() }
        }
        return decision.receipt
    }

    private fun resolved(
        request: AgentQuestionRequest,
        status: AgentQuestionStatus,
        answer: AgentQuestionAnswer?,
    ): AgentEvent.QuestionResolved = AgentEvent.QuestionResolved(
        questionId = request.questionId,
        runId = request.runId,
        status = status,
        answer = answer,
    )

    private fun sameRequest(left: AgentQuestionRequest, right: AgentQuestionRequest): Boolean =
        sameIds(left, right.conversationId, right.runId, right.questionId, right.toolCallId)

    private fun sameIds(
        request: AgentQuestionRequest,
        conversationId: String,
        runId: String,
        questionId: String,
        toolCallId: String,
    ): Boolean = request.conversationId == conversationId &&
        request.runId == runId &&
        request.questionId == questionId &&
        request.toolCallId == toolCallId

    private fun foreign() = AgentQuestionReceipt(false, "QUESTION_FOREIGN", "答案与当前问题的四个身份字段不一致")

    private fun notPending() = AgentQuestionReceipt(false, "QUESTION_NOT_PENDING", "没有等待中的问题")

    private data class Slot(
        val request: AgentQuestionRequest,
        val status: AgentQuestionStatus,
        val answer: AgentQuestionAnswer? = null,
    )

    private data class Decision(
        val receipt: AgentQuestionReceipt,
        val event: AgentEvent.QuestionResolved?,
    )

    internal companion object {
        const val TOOL_NAME = "ask_user"
    }
}
