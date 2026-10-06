package io.github.mangi.eta.agent.runtime

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
