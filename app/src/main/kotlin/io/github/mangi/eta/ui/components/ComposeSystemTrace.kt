package io.github.mangi.eta.ui.components

import android.os.Looper
import android.os.Trace
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import io.github.mangi.eta.core.AppFileLogger

/** Compiler-supplied function/source labels only; no parameters, state, or message content. */
@OptIn(InternalComposeTracingApi::class)
internal object ComposeSystemTrace {
    private var installed = false

    // Installed once before any UI composition, never swapped during a composition.
    fun install() {
        if (installed) return
        val mainThread = Looper.getMainLooper().thread
        Composer.setTracer(MainThreadCompositionTracer(
            owner = mainThread,
            enabled = { StreamDiagnosticControl.allowed && AppFileLogger.isEnabled() && Trace.isEnabled() },
            begin = Trace::beginSection,
            end = Trace::endSection,
        ))
        installed = true
    }
}

/**
 * Main-thread only: the observed jank is main-thread recomposition. No ThreadLocal,
 * stack sampling, disk logging, or Compose state is touched in this hot path.
 * Once an outer section starts, keep the gate open until its matching end even if
 * logging is disabled midway. The compiler checks isTraceInProgress at BOTH ends.
 */
@OptIn(InternalComposeTracingApi::class)
internal class MainThreadCompositionTracer(
    private val owner: Thread,
    private val enabled: () -> Boolean,
    private val begin: (String) -> Unit,
    private val end: () -> Unit,
) : CompositionTracer {
    private var depth = 0
    private val labels = HashMap<String, String>()

    override fun isTraceInProgress(): Boolean =
        Thread.currentThread() === owner && (depth > 0 || enabled())

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        // The compiler already checked the gate. Do not recheck a concurrently
        // changing logging switch between its check and this start callback.
        if (Thread.currentThread() !== owner) return
        val label = labels[info] ?: composeSystemTraceLabel(info).also {
            if (labels.size < COMPOSE_TRACE_LABEL_LIMIT) labels[info] = it
        }
        begin(label)
        depth++
    }

    override fun traceEventEnd() {
        if (Thread.currentThread() !== owner || depth == 0) return
        end()
        depth--
    }
}

internal const val COMPOSE_TRACE_LABEL_LIMIT = 512

/** android.os.Trace accepts at most 127 UTF-16 chars; avoid splitting a surrogate pair. */
internal fun composeSystemTraceLabel(info: String): String {
    val prefix = "Eta.compose:"
    var length = minOf(info.length, 127 - prefix.length)
    if (length > 0 && info[length - 1].isHighSurrogate()) length--
    return prefix + info.take(length).replace('|', '_').replace('\n', ' ').replace('\r', ' ')
}
