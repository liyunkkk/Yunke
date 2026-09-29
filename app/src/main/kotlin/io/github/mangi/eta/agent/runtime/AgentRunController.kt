package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.delegation.SubAgentPlan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()
    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled
    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    data class SteeringInput(val text: String, val imagesJson: String = "[]")
    private data class PendingSteering(val input: SteeringInput, val immediate: Boolean)
    private val steeringMessages = ArrayDeque<PendingSteering>()
    private var acceptingSteering = true
    private var stoppedSteering = emptyList<SteeringInput>()
    fun takeStoppedSteering(): List<SteeringInput> = lock.withLock {
        stoppedSteering.also { stoppedSteering = emptyList() }
    }
    @Volatile private var paused = false
    @Volatile private var checkpointPaused = false
    private val transportCallbackDepth = ThreadLocal<Int>()
    @Volatile private var pausedInterrupt = false
    private var pendingCompact: CompactRequest? = null
    private var boundaryObserver: (() -> Unit)? = null
    private var taskProgressReporter: ((String) -> Boolean)? = null

    /** 子代理预算：协调器在派发前写入，运行器读取以告知子代理；默认 null 表示不额外约束。 */
    @Volatile var subAgentBudget: SubAgentPlan? = null

    data class CompactRequest(
        val keepRecentMessages: Int? = null,
        val compressModelConfig: AgentModelClient.ModelConfig? = null,
    )

    fun cancel() {
        lock.withLock {
            if (!cancelled) stoppedSteering = steeringMessages.map { it.input }
            cancelled = true
            checkpointPaused = false
            acceptingSteering = false
            steeringMessages.clear()
            pendingCompact = null
            paused = false
            boundaryObserver = null
            taskProgressReporter = null
            pauseCondition.signalAll()
        }
        resources.forEach { resource -> runCatching { resource.cancel() } }
    }

    /** Existing interactive steering keeps its immediate-interrupt semantics. */
    fun steer(text: String): Boolean = steer(SteeringInput(text))
    fun steer(input: SteeringInput): Boolean {
        val interrupt = enqueueSteering(input) ?: return false
        interruptSteering(interrupt)
        if (!interrupt) resume()
        return true
    }
    /** Child supervision only: never cut an in-flight response to deliver guidance. */
    fun queueBoundaryGuidance(text: String): Boolean = enqueue(input = SteeringInput(text), immediate = false) != null

    internal fun enqueueSteering(input: SteeringInput): Boolean? = enqueue(input, immediate = true)
    private fun enqueue(input: SteeringInput, immediate: Boolean): Boolean? = lock.withLock {
        if (input.text.isBlank() || cancelled || !acceptingSteering || steeringMessages.size >= MAX_PENDING_STEERING) return null
        val normalized = input.copy(text = input.text.trim())
        if (normalized.text.length > MAX_STEERING_CHARS || normalized.imagesJson.length > MAX_STEERING_CHARS) return null
        if (steeringMessages.any { it.input == normalized }) return null
        if (steeringMessages.sumOf { it.input.text.length + it.input.imagesJson.length } + normalized.text.length + normalized.imagesJson.length > MAX_STEERING_TOTAL_CHARS) return null
        steeringMessages.addLast(PendingSteering(normalized, immediate))
        !paused
    }
    internal fun interruptSteering(interrupt: Boolean) { if (interrupt) interruptCurrentRequest() }

    fun requestCompact(keepRecentMessages: Int? = null, compressModelConfig: AgentModelClient.ModelConfig? = null): Boolean {
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            pendingCompact = CompactRequest(keepRecentMessages, compressModelConfig)
            paused = false
            pauseCondition.signalAll()
        }
        return true
    }
    val hasPendingCompact: Boolean get() = lock.withLock { pendingCompact != null }
    fun takePendingCompact(): CompactRequest? = lock.withLock {
        val request = pendingCompact
        pendingCompact = null
        request
    }
    private fun interruptCurrentRequest() {
        val interruptibles = resources.filter { it.interruptible }
        if (paused && interruptibles.isNotEmpty()) pausedInterrupt = true
        interruptibles.forEach { resource -> runCatching { resource.cancel() } }
    }
    fun pollSteeringMessage(): String? = pollSteeringInput()?.text
    fun pollSteeringInput(): SteeringInput? = lock.withLock { steeringMessages.pollFirst()?.input }
    fun pollSteeringOrSeal(): String? = pollSteeringInputOrSeal()?.text
    fun pollSteeringInputOrSeal(): SteeringInput? = lock.withLock {
        steeringMessages.pollFirst()?.let { return it.input }
        if (pendingCompact != null) return null
        acceptingSteering = false
        null
    }
    val hasPendingSteering: Boolean get() = lock.withLock { steeringMessages.isNotEmpty() }
    /** Only interactive steering may terminate an in-flight provider request. */
    val hasPendingImmediateSteering: Boolean get() = lock.withLock { steeringMessages.any { it.immediate } }
    val isPaused: Boolean get() = paused
    val hasPausedInterrupt: Boolean get() = pausedInterrupt
    fun consumePausedInterrupt(): Boolean = lock.withLock {
        val value = pausedInterrupt
        pausedInterrupt = false
        value
    }

    fun setPauseBoundaryObserver(observer: (() -> Unit)?) { lock.withLock { boundaryObserver = observer } }
    fun setTaskProgressReporter(reporter: ((String) -> Boolean)?) { lock.withLock { taskProgressReporter = reporter } }
    fun reportTaskProgress(summary: String): Boolean {
        if (summary.isBlank() || summary.length > 1000) return false
        val reporter = lock.withLock { if (cancelled) null else taskProgressReporter }
        return reporter?.invoke(summary.trim()) ?: false
    }

    /** Legacy cooperative-only pause; supervised children use pause() to bound stalled SSE. */
    fun pauseAtCheckpoint() { lock.withLock { if (!cancelled) checkpointPaused = true } }
    fun pause() {
        lock.withLock { if (!cancelled) paused = true }
        interruptCurrentRequest()
    }
    fun resume() {
        lock.withLock {
            checkpointPaused = false
            paused = false
            pauseCondition.signalAll()
        }
    }

    fun throwIfCancelled() {
        if ((transportCallbackDepth.get() ?: 0) > 0) {
            if (cancelled) throw AgentRunCancelledException()
            return
        }
        val observer = lock.withLock { if ((paused || checkpointPaused) && !cancelled) boundaryObserver else null }
        if (observer != null) runCatching { observer() }
        lock.withLock {
            while ((paused || checkpointPaused) && !cancelled) {
                try { pauseCondition.await() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); cancelled = true }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    internal fun <T> withTransportCallback(block: () -> T): T {
        val depth = transportCallbackDepth.get() ?: 0
        transportCallbackDepth.set(depth + 1)
        try { return block() }
        finally { if (depth == 0) transportCallbackDepth.remove() else transportCallbackDepth.set(depth) }
    }
    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try { cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw AgentRunCancelledException() }
        finally { binding.close() }
        throwIfCancelled()
    }
    fun register(interruptible: Boolean = false, cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel, interruptible)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }
    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() { closeBlock() }
    }
    private class CancellableResource(private val cancelBlock: () -> Unit, val interruptible: Boolean) {
        private val cancelled = AtomicBoolean(false)
        fun cancel() { if (cancelled.compareAndSet(false, true)) cancelBlock() }
    }
    private companion object {
        const val MAX_PENDING_STEERING = 16
        // Matches AgentRuntimeClient.steerRun's 64K wire cap: formatted file references pushed
        // ordinary supplements past the old 4000 and they were refused without a reason.
        const val MAX_STEERING_CHARS = 64_000
        const val MAX_STEERING_TOTAL_CHARS = 192_000
    }
}

internal class AgentRunCancelledException(
    val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    val reasoningContent: String = "",
) : RuntimeException("Agent run cancelled")
