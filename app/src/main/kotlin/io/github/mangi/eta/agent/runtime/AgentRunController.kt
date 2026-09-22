package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient


import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean
        get() = cancelled

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    data class SteeringInput(val text: String, val imagesJson: String = "[]")
    private val steeringMessages = ArrayDeque<SteeringInput>()
    private var acceptingSteering = true
    private var stoppedSteering = emptyList<SteeringInput>()

    fun takeStoppedSteering(): List<SteeringInput> = lock.withLock {
        stoppedSteering.also { stoppedSteering = emptyList() }
    }
    @Volatile
    private var paused = false
    @Volatile
    private var checkpointPaused = false
    private val transportCallbackDepth = ThreadLocal<Int>()
    @Volatile
    private var pausedInterrupt = false
    private var pendingCompact: CompactRequest? = null

    data class CompactRequest(
        val keepRecentMessages: Int? = null,
        val compressModelConfig: AgentModelClient.ModelConfig? = null,
    )

    fun cancel() {
        lock.withLock {
            if (!cancelled) stoppedSteering = steeringMessages.toList()
            cancelled = true
            checkpointPaused = false
            acceptingSteering = false
            steeringMessages.clear()
            pendingCompact = null
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /**
     * 将补充指令排入下一次模型请求，仍属于当前逻辑 turn。
     *
     * 流式模型请求会被打断（取消已注册的 EventSource），已写出的正文由 AgentLoop
     * 保留后立刻注入 steering。工具批次仍跑完，不在这里取消。
     */
    fun steer(text: String): Boolean = steer(SteeringInput(text))

    fun steer(input: SteeringInput): Boolean {
        val interrupt = enqueueSteering(input) ?: return false
        interruptSteering(interrupt)
        if (!interrupt) {
            // 暂停时流已经拆掉。入队后唤醒循环，避免 Binder/语音直连时追加排着却一直挂起。
            resume()
        }
        return true
    }

    /** Session records its accepted event before interrupting the provider/allowing completion. */
    internal fun enqueueSteering(input: SteeringInput): Boolean? = lock.withLock {
        if (input.text.isBlank() || cancelled || !acceptingSteering) return null
        steeringMessages.addLast(input.copy(text = input.text.trim()))
        !paused
    }

    internal fun interruptSteering(interrupt: Boolean) {
        if (interrupt) interruptCurrentRequest()
    }

    /**
     * 压缩排队到响应和工具批次完成后的安全边界，不打断当前 SSE。
     * 工具批次不会被取消。压缩请求解开暂停，并在安全边界持续执行。
     */
    fun requestCompact(
        keepRecentMessages: Int? = null,
        compressModelConfig: AgentModelClient.ModelConfig? = null,
    ): Boolean {
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            pendingCompact = CompactRequest(
                keepRecentMessages = keepRecentMessages,
                compressModelConfig = compressModelConfig,
            )
            paused = false
            pauseCondition.signalAll()
        }
        return true
    }

    val hasPendingCompact: Boolean
        get() = lock.withLock { pendingCompact != null }

    fun takePendingCompact(): CompactRequest? =
        lock.withLock {
            val request = pendingCompact
            pendingCompact = null
            request
        }

    /**
     * 只取消当前请求占用的资源（SSE），不把整个 run 标成 cancelled。
     * 暂停中的 steering 不再次打断：生成流已在 pause() 里拆掉。
     */
    private fun interruptCurrentRequest() {
        val interruptibles = resources.filter { it.interruptible }
        if (paused && interruptibles.isNotEmpty()) {
            pausedInterrupt = true
        }
        interruptibles.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /** 默认逐条消费，避免后来的补充指令越过前一条的模型回合。 */
    fun pollSteeringMessage(): String? = pollSteeringInput()?.text

    fun pollSteeringInput(): SteeringInput? = lock.withLock { steeringMessages.pollFirst() }

    /**
     * 自然结束前原子地消费最后一条 steering；若队列为空则永久关闭本 run 的接收入口。
     * 这样 Service 不会在 loop 已返回后仍把补充指令误报为已接收。
     */
    fun pollSteeringOrSeal(): String? = pollSteeringInputOrSeal()?.text

    fun pollSteeringInputOrSeal(): SteeringInput? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it }
            if (pendingCompact != null) return null
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    val isPaused: Boolean
        get() = paused

    val hasPausedInterrupt: Boolean
        get() = pausedInterrupt

    fun consumePausedInterrupt(): Boolean = lock.withLock {
        val value = pausedInterrupt
        pausedInterrupt = false
        value
    }

    /** Stop at the next cooperative boundary without replaying an in-flight request or tool. */
    fun pauseAtCheckpoint() { lock.withLock { if (!cancelled) checkpointPaused = true } }

    fun pause() {
        lock.withLock { paused = true }
        interruptCurrentRequest()
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            checkpointPaused = false
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        // Network callbacks must finish the in-flight response, not wait for its owner.
        // Cancellation remains effective, including checks nested in provider callbacks.
        if ((transportCallbackDepth.get() ?: 0) > 0) {
            if (cancelled) throw AgentRunCancelledException()
            return
        }
        lock.withLock {
            while ((paused || checkpointPaused) && !cancelled) {
                try {
                    pauseCondition.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelled = true
                }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    /** Keep transport callbacks nonblocking; only the collecting worker waits at a checkpoint. */
    internal fun <T> withTransportCallback(block: () -> T): T {
        val depth = transportCallbackDepth.get() ?: 0
        transportCallbackDepth.set(depth + 1)
        try {
            return block()
        } finally {
            if (depth == 0) transportCallbackDepth.remove() else transportCallbackDepth.set(depth)
        }
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    /**
     * @param interruptible true 表示可被 steering 打断（当前模型 SSE）。
     * 工具执行器等长驻资源必须保持 false，避免补充指令把整批工具一起关掉。
     */
    fun register(interruptible: Boolean = false, cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel, interruptible)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(
        private val cancelBlock: () -> Unit,
        val interruptible: Boolean,
    ) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }
}

internal class AgentRunCancelledException(
    val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    val reasoningContent: String = "",
) : RuntimeException("Agent run cancelled")
