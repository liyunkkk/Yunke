package io.github.mangi.eta.ui.components

import android.os.Trace
import java.util.concurrent.atomic.AtomicLong

/**
 * Point markers that prove a chat card content function body actually ran.
 *
 * Compose skipped content has no body execution at all, so a marker separates
 * "the parent list item lambda ran" (chat.row.commit) from "this card content
 * really executed". Each marker is a zero-length beginSection/endSection pair:
 * it is a point event, NOT a render, measure, layout, draw or commit timing.
 *
 * Boundaries kept here:
 * - the platform gate is read at emission time; while tracing is disabled
 *   nothing is emitted. There is no snapshot state, no LaunchedEffect, no
 *   per-frame collector and no logcat output in this file, so the markers can
 *   never request recomposition or change skippability.
 * - only the literal marker kind and the anonymous mount number are
 *   interpolated. Message text, message id, row key, hashes, tool arguments and
 *   URLs never reach a marker name.
 * - [nextChatBodyTraceMount] is its own namespace. It is deliberately NOT the
 *   chat.row r / chat.list i / chat.list.vN identity produced by
 *   ChatScrollDiagnostics and may only be correlated with those by time. That
 *   row identity is not reusable here: its keyed id map is private to the other
 *   file and using it would require passing a row key into content code.
 */
private val chatBodyTraceMounts = AtomicLong()

/** Marker kinds used by the ChatMessageItem.kt entry points; anything else is a bug. */
internal val CHAT_BODY_TRACE_KINDS = setOf(
    "thinking",
    "tool",
    "md",
    "md.doc",
    "md.phase.loading",
    "md.phase.error",
    "md.phase.success",
)

/** Anonymous, monotonic, process-local mount number. Never derived from a key or an id. */
internal fun nextChatBodyTraceMount(): Long = chatBodyTraceMounts.incrementAndGet()

/** Literal-only marker name; the only variable part is the anonymous mount number. */
internal fun chatBodyTraceName(kind: String, mountId: Long): String = "chat.body.$kind m=$mountId"

/** Point-marker sink, so pure JVM tests stay off android.os.Trace. */
internal fun interface ChatBodyTraceSink {
    fun section(name: String)
}

/**
 * Emits exactly one point marker when [enabled]; a disabled gate emits nothing.
 * Kept free of android.os.Trace so both paths are testable on the JVM.
 */
internal fun emitChatBodyTrace(
    kind: String,
    mountId: Long,
    enabled: Boolean,
    sink: ChatBodyTraceSink,
) {
    if (!enabled) return
    sink.section(chatBodyTraceName(kind, mountId))
}

/**
 * The one call a content entry point makes from its own body, e.g.
 * `SideEffect { traceChatBodyRun("thinking", bodyTraceMount) }`. The gate is
 * read here, per emission, and is never cached in snapshot state.
 */
/** A commit count, never the elapsed cost of composing the content body. */
internal fun interface ChatBodyCommitSink {
    fun record(stage: String)
}

internal fun emitChatBodyDiagnosticCommit(kind: String, enabled: Boolean, sink: ChatBodyCommitSink) {
    if (!enabled || kind !in CHAT_BODY_TRACE_KINDS) return
    sink.record("render.compose")
}

internal fun traceChatBodyRun(kind: String, mountId: Long) {
    // Runs in the existing SideEffect: no new state, effect, body wrapper or recomposition request.
    if (StreamPerformanceDiagnostics.enabled) {
        emitChatBodyDiagnosticCommit(kind, enabled = true, sink = AndroidChatBodyCommitSink)
    }
    emitChatBodyTrace(kind, mountId, Trace.isEnabled(), AndroidChatBodyTraceSink)
}

private object AndroidChatBodyCommitSink : ChatBodyCommitSink {
    override fun record(stage: String) {
        StreamPerformanceDiagnostics.record(stage, value = 1)
    }
}

private object AndroidChatBodyTraceSink : ChatBodyTraceSink {
    override fun section(name: String) {
        // Zero-length point marker, not an elapsed measurement.
        Trace.beginSection(name)
        Trace.endSection()
    }
}
