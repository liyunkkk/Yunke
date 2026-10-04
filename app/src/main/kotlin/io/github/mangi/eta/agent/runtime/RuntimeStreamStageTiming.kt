package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics

/**
 * Runtime-only adapter: disabled calls execute directly, without a measurement lambda/clock/trace.
 * The existing gate is a process-local foreground UI stream session (including its tail), not a
 * global runtime recorder. MainActivity and AgentRuntimeService currently share the default app
 * process. Call sites supply only finite static stages; never event payloads, IDs or arguments.
 */
internal fun <T> measureRuntimeStreamStage(stage: String, block: () -> T): T {
    if (!StreamPerformanceDiagnostics.enabled) return block()
    return StreamPerformanceDiagnostics.measure(stage, block = block)
}
