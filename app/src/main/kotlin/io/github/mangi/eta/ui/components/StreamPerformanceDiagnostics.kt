package io.github.mangi.eta.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Trace
import android.view.FrameMetrics
import android.util.Printer
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

/**
 * 点开工具、推理或工作过程后的逐帧记录。5 秒汇总看不出尖峰落在哪一下，这里只在点击后
 * 的有限窗口内逐帧打点，并记下主线程上耗时较长的消息，定位帧开始前是谁占住了主线程。
 * 只记录耗时和 Handler/回调的类名，不记录消息正文或 ID。
 */
internal class ToggleProbe(val kind: String, val expanded: Boolean, val startNs: Long) {
    private val frames = ArrayList<String>(TOGGLE_PROBE_FRAMES)
    private val messages = ArrayList<String>(TOGGLE_PROBE_MAX_MESSAGES)
    private var messageStartNs = 0L
    private var messageName: String? = null
    var droppedMessages = 0
        private set
    @Volatile var finished = false

    val printer = Printer { line ->
        if (finished) return@Printer
        val now = System.nanoTime()
        if (line.startsWith(">>>>>")) {
            messageStartNs = now
            messageName = line
        } else if (line.startsWith("<<<<<")) {
            val started = messageStartNs
            val name = messageName
            messageStartNs = 0L
            messageName = null
            // 装上 printer 时正在处理的那条消息没有起点，忽略。
            if (started == 0L || name == null) return@Printer
            val duration = now - started
            if (duration < TOGGLE_PROBE_SLOW_MESSAGE_NS) return@Printer
            val entry = "atMs=${(started - startNs) / 1_000_000} durUs=${duration / 1000} " +
                "msg=${toggleProbeMessageName(name)}"
            synchronized(this) {
                if (messages.size < TOGGLE_PROBE_MAX_MESSAGES) messages += entry else droppedMessages++
            }
        }
    }

    /** 返回 true 表示窗口已满，应当收尾。 */
    @Synchronized fun addFrame(line: String): Boolean {
        frames += line
        return frames.size >= TOGGLE_PROBE_FRAMES
    }

    fun expired(now: Long): Boolean = now - startNs >= TOGGLE_PROBE_MAX_NS

    @Synchronized fun report(sessionId: String): List<String> {
        val prefix = "StreamDiag id=$sessionId toggle=$kind expanded=$expanded"
        return buildList {
            add("$prefix frames=${frames.size} slowMessages=${messages.size} droppedMessages=$droppedMessages")
            frames.forEachIndexed { index, frame -> add("$prefix frame=$index $frame") }
            messages.forEach { add("$prefix main $it") }
        }
    }
}

/** 取 Looper 日志里的 Handler 类名和回调类名，去掉对象哈希与 what 值以外的内容。 */
internal fun toggleProbeMessageName(line: String): String {
    val handler = line.substringAfter("(", "").substringBefore(")", "")
    val callback = line.substringAfter("} ", "").substringBefore(": ").substringBefore("@")
    // 回调名来自 Runnable.toString()，自定义 toString 可能带字段值，只留开头的类名。
    fun className(raw: String) = raw.takeWhile { it.isLetterOrDigit() || it in "_.$" }
    return "${className(handler)}/${className(callback)}".take(160)
}

internal const val TOGGLE_PROBE_FRAMES = 30
internal const val TOGGLE_PROBE_MAX_NS = 1_000_000_000L
internal const val TOGGLE_PROBE_SLOW_MESSAGE_NS = 4_000_000L
internal const val TOGGLE_PROBE_MAX_MESSAGES = 40
// 点击前一帧也收进来，看点击之前主线程是否已经在忙。
private const val FRAME_PROBE_LEAD_NS = 17_000_000L

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
    @Volatile private var probe: ToggleProbe? = null
    private var probeReporter: ((ToggleProbe) -> Unit)? = null
    private var probeTimeoutHandler: Handler? = null

    /**
     * 在主线程的点击回调里调用。没有诊断会话（不在输出中）时什么都不做。
     * 上一次点击的窗口还没收完就再点，先把上一次的结果写出来。
     */
    fun markToggle(kind: String, expanded: Boolean) {
        if (active == null) return
        val reporter = probeReporter ?: return
        probe?.let { finishProbe(it, reporter) }
        val next = ToggleProbe(kind, expanded, System.nanoTime())
        // 与 finishProbe 同一把锁：装上 printer 和登记当前窗口必须一起发生，
        // 否则诊断线程可能在两步之间收尾，留下一个一直开着的 Looper 日志。
        synchronized(this) {
            probe = next
            Looper.getMainLooper().setMessageLogging(next.printer)
        }
        // 点击后没有新帧也要收尾，Looper 日志不能一直开着。
        probeTimeoutHandler?.postDelayed({ finishProbe(next, reporter) }, TOGGLE_PROBE_MAX_NS / 1_000_000 + 100)
    }

    private fun finishProbe(target: ToggleProbe, reporter: (ToggleProbe) -> Unit) {
        synchronized(this) {
            if (target.finished) return
            target.finished = true
            if (probe === target) {
                probe = null
                Looper.getMainLooper().setMessageLogging(null)
            }
        }
        reporter(target)
    }

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
        // 上一个会话留下的窗口先收掉，不能让它的 Looper 日志跟着新会话一直开着。
        probe?.let { previous -> probeReporter?.let { finishProbe(previous, it) } }
        val reportProbe: (ToggleProbe) -> Unit = { target ->
            handler.post { runCatching { target.report(session.id).forEach(AndroidAgentLogger::info) } }
        }
        probeReporter = reportProbe
        probeTimeoutHandler = handler
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
                probe?.let { target ->
                    val intended = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                    if (!target.finished && intended >= target.startNs - FRAME_PROBE_LEAD_NS) {
                        val line = "sinceTapMs=${(intended - target.startNs) / 1_000_000} " +
                            "totalUs=${total / 1000} deadlineUs=${deadline / 1000} " +
                            "miss=${if (deadline > 0 && total > deadline) 1 else 0} " +
                            "unknownUs=${unknown / 1000} inputUs=${input / 1000} " +
                            "animUs=${animation / 1000} layoutUs=${layout / 1000} drawUs=${draw / 1000} " +
                            "syncUs=${sync / 1000} cmdUs=${command / 1000} vsyncLateUs=${late.coerceAtLeast(0) / 1000}"
                        if (target.addFrame(line) || target.expired(System.nanoTime())) {
                            finishProbe(target, reportProbe)
                        }
                    } else if (target.expired(System.nanoTime())) {
                        finishProbe(target, reportProbe)
                    }
                }
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        handler.post {
            AndroidAgentLogger.info("StreamDiag id=${session.id} start=1 intervalMs=5000 frameValue=deadlineMiss histogramMs=16,32,50,100 parts=unknown,animation,command,swap,unaccounted,vsyncLate")
            handler.postDelayed(periodic, 5000)
        }
        return {
            window.removeOnFrameMetricsAvailableListener(listener)
            if (active === session) {
                probe?.let { finishProbe(it, reportProbe) }
                active = null
                probeReporter = null
                probeTimeoutHandler = null
            }
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
