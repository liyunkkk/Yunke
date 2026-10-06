package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics

/** 将高频文本增量合并后写入 checkpoint，结构化边界则同步落盘。 */
internal class AgentRunCheckpointRecorder private constructor(
    context: Context,
    private val runId: String,
    private val nanoTime: () -> Long,
) {
    private val appContext = context.applicationContext
    private var nextSortIndex = 0
    private var pendingDelta: AgentEvent.AssistantBlockDelta? = null
    private var sealed = false
    private var lastFlushNanos = nanoTime()
    // Diagnostic-only clocks never participate in the existing flush schedule.
    private var pendingObservedAtNs: Long? = null
    private var pendingDiagnosticGeneration: Long? = null
    private var pendingObservedDeltas = 0L

    fun accept(event: AgentEvent) {
        val requested = if (StreamPerformanceDiagnostics.enabled) System.nanoTime() else null
        acceptLocked(event, requested)
    }

    @Synchronized private fun acceptLocked(event: AgentEvent, requested: Long?) {
        if (requested != null) {
            StreamPerformanceDiagnostics.record("runtime.checkpoint.lockWait", System.nanoTime() - requested)
        }
        check(!sealed) { "Checkpoint is sealed" }
        val checkpointEvent = event.recoveryProjection() ?: return
        if (checkpointEvent is AgentEvent.AssistantBlockDelta) {
            val pending = pendingDelta
            if (
                pending != null &&
                pending.round == checkpointEvent.round &&
                pending.kind == checkpointEvent.kind &&
                pending.index == checkpointEvent.index
            ) {
                pendingDelta = StreamPerformanceDiagnostics.measure("runtime.checkpoint.merge", checkpointEvent.deltaChars.toLong()) {
                    pending.copy(
                        deltaChars = pending.deltaChars + checkpointEvent.deltaChars,
                        delta = pending.delta + checkpointEvent.delta,
                    )
                }
                if (pendingDiagnosticGeneration != StreamPerformanceDiagnostics.currentSessionToken()) {
                    clearPendingObservations()
                } else if (pendingDiagnosticGeneration != null) {
                    pendingObservedDeltas++
                }
            } else {
                flushPendingDelta("runtime.checkpoint.flush.boundary")
                // Capture before creation: off-to-on during assignment must not start a partial sample.
                pendingDiagnosticGeneration = StreamPerformanceDiagnostics.currentSessionToken()
                pendingObservedAtNs = pendingDiagnosticGeneration?.let { System.nanoTime() }
                pendingDelta = checkpointEvent
                pendingObservedDeltas = if (pendingDiagnosticGeneration != null) 1L else 0L
            }
            val elapsed = nanoTime() - lastFlushNanos
            if (
                pendingDelta.orEmptyChars() >= MAX_BUFFERED_DELTA_CHARS ||
                elapsed >= MAX_BUFFERED_DELTA_NANOS
            ) {
                flushPendingDelta(
                    if (pendingDelta.orEmptyChars() >= MAX_BUFFERED_DELTA_CHARS)
                        "runtime.checkpoint.flush.size" else "runtime.checkpoint.flush.timer",
                )
            }
            return
        }

        flushPendingDelta("runtime.checkpoint.flush.boundary")
        append(checkpointEvent)
    }

    /** 把最后一段增量提交到日志；日志由结果 ACK 或中断恢复负责删除。 */
    @Synchronized fun seal() {
        if (sealed) return
        sealed = true
        flushPendingDelta("runtime.checkpoint.flush.seal")
    }

    @Synchronized fun discard() {
        sealed = true
        pendingDelta = null
        clearPendingObservations()
        AgentRunCheckpointStore.remove(appContext, runId)
    }

    // These timings are inside the existing recorder monitor, not monitor-wait measurements.
    // Flush includes append; append is caller wall time, including the store's existing blocking IO.
    private fun flushPendingDelta(stage: String) {
        val event = pendingDelta ?: return
        measureRuntimeStreamStage(stage) {
            val diagnosticGeneration = pendingDiagnosticGeneration
            val observedAt = pendingObservedAtNs
            if (diagnosticGeneration != null && observedAt != null &&
                StreamPerformanceDiagnostics.currentSessionToken() == diagnosticGeneration
            ) {
                // Each record rechecks the serial atomically; a session switch cannot receive old data.
                StreamPerformanceDiagnostics.recordForSession(diagnosticGeneration,
                    "runtime.checkpoint.buffer.chars", value = event.deltaChars.toLong())
                StreamPerformanceDiagnostics.recordForSession(diagnosticGeneration,
                    "runtime.checkpoint.buffer.events", value = pendingObservedDeltas)
                StreamPerformanceDiagnostics.recordForSession(diagnosticGeneration,
                    "runtime.checkpoint.buffer.residency", System.nanoTime() - observedAt)
            }
            clearPendingObservations()
            pendingDelta = null
            append(event)
            lastFlushNanos = nanoTime()
        }
    }

    private fun clearPendingObservations() {
        pendingObservedAtNs = null
        pendingDiagnosticGeneration = null
        pendingObservedDeltas = 0L
    }

    private fun append(event: AgentEvent) {
        measureRuntimeStreamStage("runtime.checkpoint.append") {
            AgentRunCheckpointStore.append(
                context = appContext,
                runId = runId,
                sortIndex = nextSortIndex++,
                event = event,
            )
        }
    }

    private fun AgentEvent.AssistantBlockDelta?.orEmptyChars(): Int = this?.deltaChars ?: 0

    companion object {
        private const val MAX_BUFFERED_DELTA_CHARS = 512
        private const val MAX_BUFFERED_DELTA_NANOS = 250_000_000L

        fun create(
            context: Context,
            request: AgentRuntimeWire.RunRequest,
            nanoTime: () -> Long = System::nanoTime,
        ): AgentRunCheckpointRecorder? {
            if (!AgentRunCheckpointStore.start(context, request)) return null
            return AgentRunCheckpointRecorder(
                context = context,
                runId = request.runId,
                nanoTime = nanoTime,
            )
        }
    }
}
