package io.github.mangi.eta.agent.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import io.github.mangi.eta.ui.app.StreamUiEventDiagnostics

internal fun <T> withRuntimeDiagnosticEvent(runId: String?, replay: Boolean, block: () -> T): T {
    if (!StreamPerformanceDiagnostics.enabled) return block()
    // A receive sequence is allocated once and survives replay buffering via a bounded weak link.
    val attribution = StreamPerformanceDiagnostics.newEventAttribution(runId, replay)
    return StreamPerformanceDiagnostics.withAttribution(attribution, block)
}

internal fun bindRuntimeDiagnosticEvent(event: AgentEvent?) {
    if (event == null || !StreamPerformanceDiagnostics.enabled) return
    val captured = StreamPerformanceDiagnostics.captureAttribution() ?: return
    StreamPerformanceDiagnostics.bindEvent(event,
        captured.copy(kind = StreamUiEventDiagnostics.eventStage(event).removePrefix("ui.event.")))
}

internal fun <T> withRuntimeDecodedEvent(event: AgentEvent, block: () -> T): T {
    if (!StreamPerformanceDiagnostics.enabled) return block()
    val captured = StreamPerformanceDiagnostics.captureAttribution()
    val attribution = StreamPerformanceDiagnostics.eventAttribution(event, null, null, null,
        StreamUiEventDiagnostics.eventStage(event).removePrefix("ui.event."), captured?.replay ?: true)
    return StreamPerformanceDiagnostics.withAttribution(attribution, block)
}

/**
 * Runtime-only adapter: disabled calls execute directly, without a measurement lambda/clock/trace.
 * The existing gate is a process-local foreground UI stream session (including its tail), not a
 * global runtime recorder. MainActivity and AgentRuntimeService currently share the default app
 * process. Call sites supply only finite static stages; never event payloads, IDs or arguments.
 */
internal inline fun <T> measureRuntimeStreamStage(stage: String, crossinline block: () -> T): T {
    if (!StreamPerformanceDiagnostics.enabled) return block()
    return StreamPerformanceDiagnostics.measure(stage) { block() }
}

/**
 * All Client/Attach receive handlers enqueue on Main, in Messenger receive order. One shared
 * decoder (not a pool or one thread per run) posts immutable decoded callbacks back to the same
 * Main queue in that order. ACKs/results use this path too, so they cannot overtake pending
 * event decoding. No recycled Message crosses threads: callers capture its Bundle/values only.
 * The handlers/delivery closures retain their own run owner; there is no global 'current run'.
 */
internal object RuntimeStreamDispatch {
    val decoder: Handler by lazy {
        Handler(HandlerThread("eta-runtime-decode").apply { start() }.looper)
    }
    val main: Handler by lazy { Handler(Looper.getMainLooper()) }
}

internal fun dispatchRuntimeDecoded(decode: () -> (() -> Unit)?) {
    val attribution = StreamPerformanceDiagnostics.captureAttribution()
    RuntimeStreamDispatch.decoder.post {
        val apply = StreamPerformanceDiagnostics.withAttribution(attribution, decode)
        if (apply != null) {
            RuntimeStreamDispatch.main.post {
                StreamPerformanceDiagnostics.withAttribution(attribution, apply)
            }
        }
    }
}

/** Binder death is a terminal boundary too: enqueue it after already-received Main messages. */
internal fun dispatchRuntimeTerminalBarrier(apply: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        dispatchRuntimeDecoded { apply }
    } else {
        RuntimeStreamDispatch.main.post { dispatchRuntimeDecoded { apply } }
    }
}

/** Main receive-side replay classification; delivery.isLive changes later, at the apply barrier. */
internal class RuntimeAttachReceiveGate {
    private enum class State { REPLAYING, LIVE, CLOSED }
    private var state = State.REPLAYING
    val isLive: Boolean get() = state == State.LIVE
    fun attachResponse(attached: Boolean) {
        if (state == State.REPLAYING) state = if (attached) State.LIVE else State.CLOSED
    }
    fun result() { state = State.CLOSED }
}
