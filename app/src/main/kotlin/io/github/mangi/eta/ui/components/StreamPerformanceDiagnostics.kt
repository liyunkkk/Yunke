package io.github.mangi.eta.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.delay
import java.util.UUID

/** Fixed-size aggregate; values are counts/lengths only, never message text or IDs. */
internal class StreamTimingStats {
    var count = 0L
    var totalNs = 0L
    var maxNs = 0L
    var valueSum = 0L
    var valueMax = 0L
    val buckets = LongArray(5)
    fun add(ns: Long, value: Long) {
        val duration = ns.coerceAtLeast(0)
        count++
        totalNs += duration
        maxNs = maxOf(maxNs, duration)
        valueSum += value.coerceAtLeast(0)
        valueMax = maxOf(valueMax, value)
        buckets[when {
            duration <= 16_000_000 -> 0
            duration <= 32_000_000 -> 1
            duration <= 50_000_000 -> 2
            duration <= 100_000_000 -> 3
            else -> 4
        }]++
    }
    fun summary(): String = "n=$count avgUs=${totalNs / count.coerceAtLeast(1) / 1000} maxUs=${maxNs / 1000} " +
        "b16_32_50_100_over=${buckets.joinToString(",")} valueSum=$valueSum valueMax=$valueMax"
}

/** One visible chat window. Logging is on its worker; hot paths only update bounded counters. */
internal object StreamPerformanceDiagnostics {
    private class Session {
        val id = UUID.randomUUID().toString().take(8)
        val started = System.nanoTime()
        val stats = linkedMapOf<String, StreamTimingStats>()
        var closed = false
        private var lastDeltaNs = 0L
        @Synchronized fun record(stage: String, ns: Long, value: Long) {
            if (closed || (stage !in stats && stats.size >= 64)) return
            if (stage == "ui.delta.received") {
                val now = System.nanoTime()
                if (lastDeltaNs != 0L) stats.getOrPut("ui.delta.gap") { StreamTimingStats() }.add(now - lastDeltaNs, 0)
                lastDeltaNs = now
            }
            stats.getOrPut(stage) { StreamTimingStats() }.add(ns, value)
        }
        fun report(final: Boolean): List<String> {
            val snapshot = synchronized(this) {
                val snapshot = stats.toMap()
                stats.clear()
                if (final) closed = true
                snapshot
            }
            // Format outside the lock, and keep each line below logcat's entry limit.
            val prefix = "StreamDiag id=$id final=$final elapsedMs=${(System.nanoTime()-started)/1_000_000}"
            return snapshot.map { (stage, stats) -> "$prefix stage=$stage ${stats.summary()}" }
                .ifEmpty { listOf("$prefix empty=1") }
        }
    }
    @Volatile private var active: Session? = null

    fun record(stage: String, ns: Long = 0, value: Long = 0) {
        active?.record(stage, ns, value)
    }

    fun <T> measure(stage: String, value: Long = 0, block: () -> T): T {
        val session = active ?: return block()
        val started = System.nanoTime()
        Trace.beginSection("Eta.$stage")
        try { return block() } finally {
            Trace.endSection()
            session.record(stage, System.nanoTime() - started, value)
        }
    }

    fun attach(window: Window): () -> Unit {
        val session = Session()
        active = session
        val thread = HandlerThread("Eta-StreamDiag").apply { start() }
        val handler = Handler(thread.looper)
        fun emit(final: Boolean) {
            runCatching {
                val runtime = Runtime.getRuntime()
                session.record("heap.usedBytes", 0, runtime.totalMemory() - runtime.freeMemory())
                session.report(final).forEach(AndroidAgentLogger::info)
            }
        }
        val periodic = object : Runnable {
            override fun run() { emit(false); handler.postDelayed(this, 5000) }
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, dropped ->
            if (frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 1L) {
                val total = frame.getMetric(FrameMetrics.TOTAL_DURATION)
                val deadline = frame.getMetric(FrameMetrics.DEADLINE)
                val unknown = frame.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
                val input = frame.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
                val animation = frame.getMetric(FrameMetrics.ANIMATION_DURATION)
                val layout = frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                val draw = frame.getMetric(FrameMetrics.DRAW_DURATION)
                val sync = frame.getMetric(FrameMetrics.SYNC_DURATION)
                val command = frame.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
                val swap = frame.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
                val gpu = frame.getMetric(FrameMetrics.GPU_DURATION)
                session.record("frame.total", total, if (deadline > 0 && total > deadline) 1 else 0)
                session.record("frame.layout", layout, 0)
                session.record("frame.draw", draw, 0)
                session.record("frame.sync", sync, 0)
                session.record("frame.gpu", gpu, 0)
                session.record("frame.input", input, 0)
                session.record("frame.unknown", unknown, 0)
                session.record("frame.animation", animation, 0)
                session.record("frame.command", command, 0)
                session.record("frame.swap", swap, 0)
                val gap = frameUnaccountedNs(total, unknown, input, animation, layout, draw, sync, command, swap)
                if (gap >= 0) session.record("frame.unaccounted", gap, 0)
                else session.record("frame.overlap", -gap, 1)
                val late = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP) -
                    frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                session.record("frame.vsyncLate", late.coerceAtLeast(0), if (late > 8_333_333L) 1 else 0)
                session.record("frame.metricsDropped", 0, dropped.toLong())
                session.record("frame.deadline", deadline, 0)
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        handler.post {
            AndroidAgentLogger.info("StreamDiag id=${session.id} start=1 intervalMs=5000 frameValue=deadlineMiss histogramMs=16,32,50,100 parts=unknown,animation,command,swap,unaccounted,vsyncLate")
            handler.postDelayed(periodic, 5000)
        }
        return {
            window.removeOnFrameMetricsAvailableListener(listener)
            if (active === session) active = null
            handler.removeCallbacks(periodic)
            handler.post { emit(true); thread.quitSafely() }
        }
    }
}

/** TOTAL_DURATION minus the eight non-overlapping FrameMetrics parts. GPU is excluded; it overlaps command/swap. */
internal fun frameUnaccountedNs(
    total: Long,
    unknown: Long,
    input: Long,
    animation: Long,
    layout: Long,
    draw: Long,
    sync: Long,
    command: Long,
    swap: Long,
): Long = total - (unknown + input + animation + layout + draw + sync + command + swap)

@Composable
internal fun StreamPerformanceMonitor(isStreaming: Boolean) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var tailActive by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) tailActive = true else { delay(3000); tailActive = false }
    }
    DisposableEffect(view, tailActive, lifecycleState) {
        val window = view.context.findStreamActivity()?.window
        val detach = if (tailActive && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && window != null) {
            StreamPerformanceDiagnostics.attach(window)
        } else null
        onDispose { detach?.invoke() }
    }
}

private fun Context.findStreamActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findStreamActivity() else null
    else -> null
}
