package io.github.mangi.eta.agent.runtime


import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
) {
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

    private data class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
    )

    init {
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
            )
        }
    }

    /** User stop cancels resources immediately; the worker must still seal its history. */
    fun requestStop(): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
            state = State.STOPPING
        }
        controller.cancel()
        return true
    }

    var terminalResult: AgentRuntimeWire.RunResult? = null
        private set

    val isTerminal: Boolean
        get() = lock.withLock { state == State.TERMINAL }

    fun emit(event: AgentEvent): Boolean =
        lock.withLock {
            if (state != State.RUNNING) return false
            recordForReplay(event)
            subscribers.forEach { it.eventSink(event) }
            true
        }

    /**
     * Activity 被移出任务栈后 Runtime 仍可能继续执行。安全历史回放、完成确认和实时订阅
     * 共用同一把锁，保证客户端收到确认前的事件都是历史，新增事件与终态不会越过边界。
     */
    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
    ): Boolean = lock.withLock {
        if (state == State.TERMINAL) return false
        replayEvents.forEach(eventSink)
        onReplayComplete()
        subscribers += Subscriber(eventSink, resultSink)
        true
    }

    fun steer(text: String): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
        }
        // 打断 SSE 不能握着 session 锁：读线程在 emit 时要同一把锁，
        // EventSource.cancel() 又会等读线程，等于把当前回复排完才返回。
        return controller.steer(text)
    }

    @Volatile var childCompactor: ((String, Int?, io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig?) -> Boolean)? = null

    /** A captured task ID targets exactly one child; rejection never falls back to main. */
    fun requestCompact(
        keepRecentMessages: Int? = null,
        compressModelConfig: io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig? = null,
        childTaskId: String? = null,
    ): Boolean {
        lock.withLock {
            if (state != State.RUNNING) return false
        }
        return if (childTaskId == null) controller.requestCompact(keepRecentMessages, compressModelConfig)
        else childCompactor?.invoke(childTaskId, keepRecentMessages, compressModelConfig) ?: false
    }

    fun <T : AgentEvent> steer(
        text: String,
        imagesJson: String = "[]",
        eventFactory: () -> T,
    ): T? {
        val accepted = lock.withLock {
            if (state != State.RUNNING) return null
            val interrupt = controller.enqueueSteering(AgentRunController.SteeringInput(text, imagesJson)) ?: return null
            val event = eventFactory()
            recordForReplay(event)
            subscribers.forEach { subscriber -> runCatching { subscriber.eventSink(event) } }
            event to interrupt
        }
        // Never cancel network resources while holding the session lock.
        controller.interruptSteering(accepted.second)
        if (!accepted.second) controller.resume()
        return accepted.first
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
     */
    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: (AgentRuntimeWire.RunResult) -> Unit = {},
    ): Boolean {
        val terminal = lock.withLock {
            if (state != State.RUNNING && state != State.STOPPING) return false
            require(result.runId == runId) { "Result runId does not match the active session" }
            val stopped = state == State.STOPPING
            state = State.COMMITTING
            if (stopped) result.copy(ok = false, error = "已停止") else result
        }
        val commitFailure = runCatching { beforePublish(terminal) }.exceptionOrNull()
        lock.withLock {
            state = State.TERMINAL
            terminalResult = terminal
            subscribers.forEach { it.resultSink(terminal) }
            subscribers.clear()
            replayEvents.clear()
        }
        commitFailure?.let { throw it }
        return true
    }

    fun cancel(reason: String): Boolean {
        val result = lock.withLock {
            if (state != State.RUNNING) return false
            state = State.TERMINAL
            AgentRuntimeWire.RunResult(
                runId = runId,
                ok = false,
                content = "",
                error = reason,
            )
        }
        controller.cancel()
        lock.withLock {
            subscribers.forEach { it.resultSink(result) }
            subscribers.clear()
            replayEvents.clear()
        }
        return true
    }
}
