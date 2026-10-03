package io.github.mangi.eta.agent.runtime


import io.github.mangi.eta.agent.question.AgentQuestionCoordinator
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionReceipt
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 一次 Runtime run 的控制权和唯一终态。
 *
 * Service 替换、用户取消和正常完成都必须经过此对象，避免旧 run 向新 reply channel 发消息，
 * 也避免同一 run 发送两个最终结果。
 */
internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    eventSink: ((AgentEvent) -> Unit)? = null,
    resultSink: ((AgentRuntimeWire.RunResult) -> Unit)? = null,
    // Freeze once for both execution and presentation, including late terminal callbacks.
    taskSurfaceMode: AgentTaskSurfaceMode =
        runCatching { AgentTaskSurface.stored() }.getOrDefault(AgentTaskSurfaceMode.ASK),
) {
    /** “每次询问”在用户选定后改成前台或后台一次，之后不再变。 */
    @Volatile
    var taskSurfaceMode: AgentTaskSurfaceMode = taskSurfaceMode
        private set

    private val surfaceLock = Any()

    fun resolveTaskSurface(mode: AgentTaskSurfaceMode): Boolean = synchronized(surfaceLock) {
        if (taskSurfaceMode != AgentTaskSurfaceMode.ASK || mode == AgentTaskSurfaceMode.ASK) return@synchronized false
        taskSurfaceMode = mode
        true
    }

    private enum class State {
        RUNNING,
        STOPPING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    private var state = State.RUNNING
    private val replayEvents = mutableListOf<AgentEvent>()
    private val subscribers = mutableListOf<Subscriber>()
    private val afterUnlock = mutableListOf<() -> Unit>()
    private val pendingEvents = ArrayDeque<EventDelivery>()
    private var dispatchDepth = 0
    private var childCompactions = 0
    private val childCompactionsFinished = lock.newCondition()
    private val childCompactionDepth = ThreadLocal<Int>()
    private var terminalAfterChildCompactions: (() -> Unit)? = null

    // Identity matters when an interrupted attach removes its provisional subscriber.
    private class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
    )

    private class EventDelivery(val event: AgentEvent, val recipients: List<Subscriber>)

    init {
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
            )
        }
    }

    /**
     * Event/replay callbacks may reenter the session. Leaving an inner lock scope is
     * not enough: resource cancellation can wait for another thread that needs this
     * lock. The outermost scope takes its actions before unlocking, then runs all of
     * them without a session lock, even if a callback or an earlier action fails.
     */
    private inline fun <T> withSessionLock(block: () -> T): T {
        lock.lock()
        var blockFailure: Throwable? = null
        try {
            return block()
        } catch (failure: Throwable) {
            blockFailure = failure
            throw failure
        } finally {
            val actions = if (lock.holdCount == 1) {
                afterUnlock.toList().also { afterUnlock.clear() }
            } else {
                emptyList()
            }
            lock.unlock()
            var actionFailure = blockFailure
            for (action in actions) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val previous = actionFailure
                    if (previous == null) actionFailure = failure
                    else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            if (blockFailure == null) actionFailure?.let { throw it }
        }
    }

    /** User stop cancels resources after callbacks unwind; the worker still seals history. */
    fun requestStop(): Boolean = withSessionLock {
        if (state != State.RUNNING) return false
        state = State.STOPPING
        afterUnlock += { controller.cancel() }
        true
    }

    @Volatile
    var terminalResult: AgentRuntimeWire.RunResult? = null
        private set

    val isTerminal: Boolean
        get() = withSessionLock { state == State.TERMINAL }

    fun emit(event: AgentEvent): Boolean =
        withSessionLock {
            if (state != State.RUNNING && !(state == State.STOPPING &&
                (event is AgentEvent.QuestionRequested || event is AgentEvent.QuestionResolved))) return false
            publishEvent(event)
            true
        }

    /**
     * Record and snapshot recipients at acceptance, not when the queue drains. A
     * subscriber attached during a broadcast gets earlier events only via replay.
     * Nested events wait for every recipient of the current event; replay similarly
     * holds live delivery until its acknowledgement, including non-replayable events.
     */
    private fun publishEvent(event: AgentEvent) {
        recordForReplay(event)
        pendingEvents.addLast(EventDelivery(event, subscribers.toList()))
        drainEvents()
    }

    private fun drainEvents() {
        if (dispatchDepth != 0) return
        dispatchDepth++
        try {
            while (pendingEvents.isNotEmpty() && state != State.TERMINAL) {
                val delivery = pendingEvents.removeFirst()
                for (subscriber in delivery.recipients) {
                    if (state == State.TERMINAL) break
                    if (subscriber in subscribers) {
                        runCatching { subscriber.eventSink(delivery.event) }
                    }
                }
            }
            // STOPPING/COMMITTING close admission, not delivery of already accepted
            // events. Drain those before afterUnlock persistence/result publication.
            // Immediate cancellation alone cuts delivery short at its terminal boundary.
            if (state == State.TERMINAL) pendingEvents.clear()
        } finally {
            dispatchDepth--
        }
    }

    /**
     * Activity 被移出任务栈后 Runtime 仍可能继续执行。安全历史回放、完成确认和实时订阅
     * 共用同一把锁，保证客户端收到确认前的事件都是历史，新增事件与终态不会越过边界。
     */
    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
    ): Boolean = withSessionLock {
        if (state == State.TERMINAL) return false
        val history = replayEvents.toList()
        val subscriber = Subscriber(eventSink, resultSink)
        subscribers += subscriber
        var attached = false
        dispatchDepth++
        try {
            for (event in history) {
                if (state == State.TERMINAL) return false
                if (runCatching { eventSink(event) }.isFailure) return false
            }
            if (state == State.TERMINAL) return false
            if (runCatching { onReplayComplete() }.isFailure) return false
            if (state == State.TERMINAL) return false
            attached = true
            true
        } finally {
            if (!attached) subscribers.remove(subscriber)
            dispatchDepth--
            drainEvents()
        }
    }

    @Volatile var questionCoordinator: AgentQuestionCoordinator? = null

    fun submitQuestionAnswer(submission: AgentRuntimeWire.QuestionAnswerSubmission): AgentQuestionReceipt {
        val coordinator = withSessionLock {
            if (state != State.RUNNING || submission.runId != runId) return AgentQuestionReceipt(
                false, "QUESTION_RUN_NOT_ACTIVE", "该任务已停止或结束")
            questionCoordinator
        } ?: return AgentQuestionReceipt(false, "QUESTION_NOT_PENDING", "没有待回答的问题")
        // Never hold the session lock while the coordinator publishes a resolved event.
        return coordinator.submitAnswer(submission.conversationId, submission.runId,
            submission.questionId, submission.toolCallId, submission.answer)
    }

    fun questionSnapshot(conversationId: String, questionId: String, toolCallId: String):
        io.github.mangi.eta.agent.question.AgentQuestionSnapshot? = withSessionLock {
        val request = replayEvents.filterIsInstance<AgentEvent.QuestionRequested>().lastOrNull {
            it.request.conversationId == conversationId && it.request.runId == runId &&
                it.request.questionId == questionId && it.request.toolCallId == toolCallId
        }?.request ?: return null
        val resolved = replayEvents.filterIsInstance<AgentEvent.QuestionResolved>().lastOrNull {
            it.runId == runId && it.questionId == questionId
        }
        val status = resolved?.status ?: if (state == State.RUNNING) AgentQuestionStatus.Waiting else AgentQuestionStatus.Interrupted
        io.github.mangi.eta.agent.question.AgentQuestionSnapshot(request.conversationId, runId,
            questionId, toolCallId, status, resolved?.answer)
    }

    fun steer(text: String): Boolean = withSessionLock {
        if (state != State.RUNNING) return false
        val interrupt = controller.enqueueSteering(AgentRunController.SteeringInput(text)) ?: return false
        deferSteering(interrupt)
        true
    }

    /** Called under the session lock; interruption and resume must outlive every lock scope. */
    private fun deferSteering(interrupt: Boolean) {
        afterUnlock += {
            if (withSessionLock { state == State.RUNNING }) {
                controller.interruptSteering(interrupt)
                if (!interrupt) controller.resume()
            }
        }
    }

    @Volatile var childCompactor: ((String, Int?, io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig?) -> Boolean)? = null

    /**
     * A captured task ID targets exactly one child; rejection never falls back to main.
     * The child coordinator may call back while holding its own monitor. Synchronous
     * reentrant child requests must therefore reject, not invoke under an outer lock
     * or return an invented success for deferred work. Ordinary calls are admitted
     * under the lock and run outside it; sealing waits for admitted calls to unwind.
     */
    fun requestCompact(
        keepRecentMessages: Int? = null,
        compressModelConfig: io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig? = null,
        childTaskId: String? = null,
    ): Boolean {
        if (childTaskId == null) return withSessionLock {
            if (state != State.RUNNING) return false
            controller.requestCompact(keepRecentMessages, compressModelConfig)
        }
        // A child callback runs outside the session lock but can still own its coordinator.
        if (lock.isHeldByCurrentThread || (childCompactionDepth.get() ?: 0) > 0) return false
        val compactor = withSessionLock {
            if (state != State.RUNNING) return false
            val target = childCompactor ?: return false
            childCompactions++
            target
        }
        val previousDepth = childCompactionDepth.get() ?: 0
        childCompactionDepth.set(previousDepth + 1)
        try {
            return compactor(childTaskId, keepRecentMessages, compressModelConfig)
        } finally {
            try {
                withSessionLock {
                    childCompactions--
                    if (childCompactions == 0) {
                        childCompactionsFinished.signalAll()
                        terminalAfterChildCompactions?.let { afterUnlock += it }
                        terminalAfterChildCompactions = null
                    }
                }
            } finally {
                if (previousDepth == 0) childCompactionDepth.remove()
                else childCompactionDepth.set(previousDepth)
            }
        }
    }

    /**
     * The caller has already closed admission by claiming COMMITTING. Never wait
     * for a coordinator from an event/replay/child callback: it may be waiting on
     * that callback's monitor. Its last admitted child performs the commit instead.
     * A non-reentrant terminal caller still waits synchronously, releasing the
     * session lock while waiting so child callbacks and isTerminal remain usable.
     */
    private fun afterChildCompactions(action: () -> Unit) {
        if (childCompactions == 0) {
            afterUnlock += action
        } else if (lock.holdCount > 1 || (childCompactionDepth.get() ?: 0) > 0) {
            terminalAfterChildCompactions = action
        } else {
            afterUnlock += {
                awaitChildCompactions()
                action()
            }
        }
    }

    /**
     * A pending child compaction must never block the terminal seal. The wait releases
     * the session lock between timeouts, so child callbacks and isTerminal stay usable,
     * and it proceeds to the seal once the budget runs out or the thread is interrupted
     * instead of waiting forever on an unrunnable child.
     */
    private fun awaitChildCompactions() {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHILD_COMPACTION_SEAL_TIMEOUT_MS)
        var interrupted = false
        var pending = 0
        try {
            withSessionLock {
                while (childCompactions != 0) {
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0) break
                    try {
                        childCompactionsFinished.awaitNanos(remainingNanos)
                    } catch (interruptedWait: InterruptedException) {
                        // An interrupt is a stop signal, not a reason to keep waiting out the budget.
                        interrupted = true
                        break
                    }
                }
                pending = childCompactions
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (interrupted || pending != 0) {
            // Diagnostics must never decide whether the terminal result is published.
            runCatching {
                AndroidAgentLogger.warn(
                    "Runtime terminal seal wait ended: pending_child_compactions=$pending interrupted=$interrupted"
                )
            }
        }
    }

    fun <T : AgentEvent> steer(
        text: String,
        imagesJson: String = "[]",
        eventFactory: () -> T,
    ): T? = withSessionLock {
        if (state != State.RUNNING) return null
        val interrupt = controller.enqueueSteering(AgentRunController.SteeringInput(text, imagesJson)) ?: return null
        val event = eventFactory()
        if (state != State.RUNNING) return null
        publishEvent(event)
        deferSteering(interrupt)
        event
    }

    private fun recordForReplay(event: AgentEvent) {
        val projected = event.recoveryProjection() ?: return
        if (projected !is AgentEvent.AssistantBlockDelta) {
            replayEvents += projected
            return
        }
        val previous = replayEvents.lastOrNull() as? AgentEvent.AssistantBlockDelta
        if (
            previous != null &&
            previous.round == projected.round &&
            previous.kind == projected.kind &&
            previous.index == projected.index
        ) {
            replayEvents[replayEvents.lastIndex] = previous.copy(
                deltaChars = previous.deltaChars + projected.deltaChars,
                delta = previous.delta + projected.delta,
            )
        } else {
            replayEvents += projected
        }
    }

    /**
     * 先原子竞争 COMMITTING，再完成提交前副作用和结果发布。取消与替换不能越过提交胜者，
     * 因而不会出现“客户端收到取消、outbox 却留下成功结果”的分裂状态；耗时 I/O 也不持有锁。
     * [beforePublish] 必须自行吸收非致命持久化异常。
     * Reentrant completion claims the result now and commits after the outer callback unwinds.
     */
    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: (AgentRuntimeWire.RunResult) -> Unit = {},
    ): Boolean = withSessionLock {
        if (state != State.RUNNING && state != State.STOPPING) return false
        require(result.runId == runId) { "Result runId does not match the active session" }
        val terminal = if (state == State.STOPPING) result.copy(ok = false, error = "已停止") else result
        state = State.COMMITTING
        afterChildCompactions {
            val commitFailure = runCatching { beforePublish(terminal) }.exceptionOrNull()
            withSessionLock { sealTerminal(terminal) }
            commitFailure?.let { throw it }
        }
        true
    }

    fun cancel(reason: String): Boolean = withSessionLock {
        if (state != State.RUNNING) return false
        val result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = false,
            content = "",
            error = reason,
        )
        if (childCompactions == 0) {
            sealTerminal(result)
        } else {
            state = State.COMMITTING
            afterChildCompactions { withSessionLock { sealTerminal(result) } }
        }
        true
    }

    /** Seal and snapshot under lock; cleanup and isolated result callbacks run after unlock. */
    private fun sealTerminal(result: AgentRuntimeWire.RunResult) {
        state = State.TERMINAL
        terminalResult = result
        val recipients = subscribers.toList()
        subscribers.clear()
        // Terminal sessions reject attach/emit, but a concurrently admitted query
        // must still see published question evidence, not lose an accepted answer.
        // Release ordinary replay text while retaining only question lifecycle data.
        replayEvents.removeAll {
            it !is AgentEvent.QuestionRequested && it !is AgentEvent.QuestionResolved
        }
        pendingEvents.clear()
        afterUnlock += {
            try {
                controller.cancel()
            } finally {
                for (subscriber in recipients) {
                    runCatching { subscriber.resultSink(result) }
                }
            }
        }
    }

    internal companion object {
        /** Upper bound on waiting for admitted child compactions before sealing the terminal. */
        const val CHILD_COMPACTION_SEAL_TIMEOUT_MS = 10_000L
    }
}
