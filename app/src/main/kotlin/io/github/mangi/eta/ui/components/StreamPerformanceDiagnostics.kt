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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
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
 * 诊断会话期间主线程上较慢的非帧消息。帧回调本身由 FrameMetrics 逐帧记录，这里只计数。
 * printer 只在主线程被调用；读取在诊断线程，存取环形缓冲时加锁。
 * 只保留 Handler/回调类名和耗时，不保留消息正文或 ID。
 */
internal class MainThreadMessageLog(private val capacity: Int = MAIN_LOG_CAPACITY) {
    private val starts = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val names = arrayOfNulls<String>(capacity)
    private var next = 0
    private var size = 0
    private var messageStartNs = 0L
    private var messageLine: String? = null
    @Volatile var frameMessages = 0L
        private set
    @Volatile var otherMessages = 0L
        private set

    val printer = Printer { line -> onLine(line, System.nanoTime()) }

    internal fun onLine(line: String, now: Long) {
        if (line.startsWith(">>>>>")) {
            messageStartNs = now
            messageLine = line
            return
        }
        if (!line.startsWith("<<<<<")) return
        val started = messageStartNs
        val name = messageLine
        messageStartNs = 0L
        messageLine = null
        // 装上 printer 时正在处理的那条消息没有起点，忽略。
        if (started == 0L || name == null) return
        if (name.contains(CHOREOGRAPHER_FRAME_RECEIVER)) {
            frameMessages++
            return
        }
        otherMessages++
        if (now - started < SLOW_MAIN_MESSAGE_NS) return
        synchronized(this) {
            starts[next] = started
            ends[next] = now
            names[next] = name
            next = (next + 1) % capacity
            if (size < capacity) size++
        }
    }

    /** 与 [fromNs, toNs] 有重叠的慢消息，时间相对 originNs。 */
    @Synchronized fun between(fromNs: Long, toNs: Long, originNs: Long, limit: Int): List<String> {
        val out = ArrayList<String>()
        val first = (next - size + capacity) % capacity
        for (i in 0 until size) {
            val slot = (first + i) % capacity
            if (ends[slot] < fromNs || starts[slot] > toNs) continue
            if (out.size >= limit) break
            out += "atMs=${(starts[slot] - originNs) / 1_000_000} durUs=${(ends[slot] - starts[slot]) / 1000} " +
                "msg=${toggleProbeMessageName(names[slot].orEmpty())}"
        }
        return out
    }
}

/**
 * 点开工具、推理或工作过程后的记录窗口：逐帧 FrameMetrics、每帧列表状态（跟底、上提、暂停上提、
 * 哨兵位置）、被点开那一项的可见高度与内容高度、测量与绘制耗时、分帧组合进度和跟底每步位移。
 * 同一窗口既用来看展开动画的形状，也用来看掉帧落在哪一段。
 */
internal class ToggleProbe(
    val kind: String,
    val expanded: Boolean,
    val token: Int,
    val generation: Int,
    val startNs: Long,
) {
    private val frames = ArrayList<String>(TOGGLE_PROBE_FRAMES)
    private val events = ArrayList<String>()
    private var lastSample: String? = null
    var droppedEvents = 0
        private set
    var maxFrameUs = 0L
        private set
    var missedFrames = 0
        private set
    @Volatile var finished = false

    /** 返回 true 表示窗口已满，应当收尾。 */
    @Synchronized fun addFrame(line: String, totalUs: Long = 0, missed: Boolean = false): Boolean {
        frames += line
        if (totalUs > maxFrameUs) maxFrameUs = totalUs
        if (missed) missedFrames++
        return frames.size >= TOGGLE_PROBE_FRAMES
    }

    @Synchronized fun addEvent(atNs: Long, tag: String, detail: String) {
        if (events.size >= TOGGLE_PROBE_MAX_EVENTS) {
            droppedEvents++
            return
        }
        events += "ev sinceTapMs=${sinceTapMs(atNs)} tag=$tag $detail"
    }

    /** 列表状态没变的帧不重复记。返回是否记下。 */
    @Synchronized fun addSample(frameNs: Long, state: String): Boolean {
        if (state == lastSample) return false
        lastSample = state
        if (events.size >= TOGGLE_PROBE_MAX_EVENTS) {
            droppedEvents++
            return false
        }
        events += "ev sinceTapMs=${sinceTapMs(frameNs)} tag=list $state"
        return true
    }

    private fun sinceTapMs(ns: Long): String {
        val tenths = (ns - startNs) / 100_000
        return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
    }

    fun expired(now: Long): Boolean = now - startNs >= TOGGLE_PROBE_MAX_NS

    @Synchronized fun report(sessionId: String, mainMessages: List<String>): List<String> {
        val prefix = "StreamDiag id=$sessionId toggle=$kind expanded=$expanded gen=$generation"
        return buildList {
            add(
                "$prefix frames=${frames.size} maxFrameUs=$maxFrameUs missed=$missedFrames " +
                    "events=${events.size} droppedEvents=$droppedEvents slowMessages=${mainMessages.size}"
            )
            frames.forEachIndexed { index, frame -> add("$prefix frame=$index $frame") }
            events.forEach { add("$prefix $it") }
            mainMessages.forEach { add("$prefix main $it") }
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

// 120Hz 下约 1.2 秒，盖住 180ms 展开动画、跟底追赶和暂停上提的最长 1.5 秒里的大部分。
internal const val TOGGLE_PROBE_FRAMES = 150
internal const val TOGGLE_PROBE_MAX_NS = 1_250_000_000L
// 每帧最多约 6 条（两层测量、两层绘制、列表、跟底），150 帧再留余量。
internal const val TOGGLE_PROBE_MAX_EVENTS = 1_200
internal const val TOGGLE_PROBE_MAX_MESSAGES = 60
internal const val SLOW_MAIN_MESSAGE_NS = 4_000_000L
internal const val MAIN_LOG_CAPACITY = 256
private const val CHOREOGRAPHER_FRAME_RECEIVER = "Choreographer\$FrameDisplayEventReceiver"
// 点击前一帧也收进来，看点击之前主线程是否已经在忙。
private const val FRAME_PROBE_LEAD_NS = 17_000_000L
// 点击窗口以外超过这个值的帧单独记一条，最多 SPIKE_MAX_PER_SESSION 条。
internal const val SPIKE_FRAME_NS = 33_000_000L
private const val SPIKE_MAX_PER_SESSION = 40
private const val SPIKE_LOOKBACK_NS = 200_000_000L

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
    @Volatile private var mainLog: MainThreadMessageLog? = null
    private var nextToken = 0
    private var generation = 0

    /** 当前是否有点击窗口在记录；热路径先查这个，避免没在记的时候拼字符串。 */
    val probing: Boolean get() = probe?.finished == false

    /**
     * 每次点击加一。列表用 snapshotFlow 订阅它来启动逐帧采样，
     * 不在组合里读，避免点击那一帧让整页会话重组。
     */
    val probeRequests = mutableIntStateOf(0)

    /**
     * 在主线程的点击回调里调用。没有诊断会话（不在输出中）时返回 0，什么都不做。
     * 上一次点击的窗口还没收完就再点，先把上一次的结果写出来。
     * 返回这次点击的 token，被点开的那一项用它把自己的高度和组合进度记进同一个窗口。
     */
    fun markToggle(kind: String, expanded: Boolean): Int {
        if (active == null) return 0
        val reporter = probeReporter ?: return 0
        probe?.let { finishProbe(it, reporter) }
        val next = synchronized(this) {
            nextToken = if (nextToken == Int.MAX_VALUE) 1 else nextToken + 1
            ToggleProbe(kind, expanded, nextToken, generation, System.nanoTime()).also { probe = it }
        }
        // 点击后没有新帧也要收尾。
        probeTimeoutHandler?.postDelayed({ finishProbe(next, reporter) }, TOGGLE_PROBE_MAX_NS / 1_000_000 + 100)
        probeRequests.intValue++
        return next.token
    }

    /** 被点开那一项或列表记一条事件。token 不是当前窗口时丢弃。 */
    fun probeEvent(token: Int, tag: String, detail: String) {
        val target = probe ?: return
        if (target.finished || token == 0 || target.token != token) return
        target.addEvent(System.nanoTime(), tag, detail)
    }

    /** 不属于某一项的事件（跟底、暂停上提、分帧组合），记进当前窗口。 */
    fun probeNote(tag: String, detail: () -> String) {
        val target = probe ?: return
        if (target.finished) return
        target.addEvent(System.nanoTime(), tag, detail())
    }

    /** 列表每帧的状态，没变就不记。 */
    fun probeListSample(frameNs: Long, state: () -> String) {
        val target = probe ?: return
        if (target.finished) return
        target.addSample(frameNs, state())
    }

    private fun finishProbe(target: ToggleProbe, reporter: (ToggleProbe) -> Unit) {
        synchronized(this) {
            if (target.finished) return
            target.finished = true
            if (probe === target) probe = null
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
        // 上一个会话留下的窗口先收掉。
        probe?.let { previous -> probeReporter?.let { finishProbe(previous, it) } }
        // 整个诊断会话都记主线程慢消息：点击窗口和窗口外的尖峰都要能对上当时主线程在干什么。
        // 这是项目里唯一设置 Looper 日志的地方；会话结束时恢复为 null。
        val log = MainThreadMessageLog()
        mainLog = log
        Looper.getMainLooper().setMessageLogging(log.printer)
        synchronized(this) { generation++ }
        val sessionGeneration = generation
        val reportProbe: (ToggleProbe) -> Unit = { target ->
            handler.post {
                runCatching {
                    val lead = target.startNs - FRAME_PROBE_LEAD_NS
                    val messages = log.between(lead, System.nanoTime(), target.startNs, TOGGLE_PROBE_MAX_MESSAGES)
                    target.report(session.id, messages).forEach(AndroidAgentLogger::info)
                }
            }
        }
        probeReporter = reportProbe
        probeTimeoutHandler = handler
        var spikes = 0
        fun emit(final: Boolean) {
            runCatching {
                val runtime = Runtime.getRuntime()
                session.record("heap.usedBytes", 0, runtime.totalMemory() - runtime.freeMemory())
                session.record("main.frameMessages", 0, log.frameMessages)
                session.record("main.otherMessages", 0, log.otherMessages)
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
                val missed = deadline > 0 && total > deadline
                session.record("frame.total", total, if (missed) 1 else 0)
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
                val intended = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                val late = frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP) - intended
                session.record("frame.vsyncLate", late.coerceAtLeast(0), if (late > 8_333_333L) 1 else 0)
                session.record("frame.metricsDropped", 0, dropped.toLong())
                session.record("frame.deadline", deadline, 0)
                val parts = "totalUs=${total / 1000} deadlineUs=${deadline / 1000} " +
                    "miss=${if (missed) 1 else 0} " +
                    "unknownUs=${unknown / 1000} inputUs=${input / 1000} " +
                    "animUs=${animation / 1000} layoutUs=${layout / 1000} drawUs=${draw / 1000} " +
                    "syncUs=${sync / 1000} cmdUs=${command / 1000} gpuUs=${gpu / 1000} " +
                    "vsyncLateUs=${late.coerceAtLeast(0) / 1000}"
                val target = probe
                val inWindow = target != null && !target.finished && intended >= target.startNs - FRAME_PROBE_LEAD_NS
                if (inWindow) {
                    val tenths = (intended - target!!.startNs) / 100_000
                    val line = "sinceTapMs=${tenths / 10}.${kotlin.math.abs(tenths % 10)} $parts"
                    if (target.addFrame(line, total / 1000, missed) || target.expired(System.nanoTime())) {
                        finishProbe(target, reportProbe)
                    }
                } else {
                    if (target != null && target.expired(System.nanoTime())) finishProbe(target, reportProbe)
                    // 点击窗口外的大尖峰：记下这一帧和它前后的主线程慢消息。
                    if (total >= SPIKE_FRAME_NS && spikes < SPIKE_MAX_PER_SESSION) {
                        spikes++
                        val messages = log.between(intended - SPIKE_LOOKBACK_NS, intended + total, intended, 12)
                        val prefix = "StreamDiag id=${session.id} spike=$spikes gen=$sessionGeneration"
                        runCatching {
                            AndroidAgentLogger.info("$prefix $parts slowMessages=${messages.size}")
                            messages.forEach { AndroidAgentLogger.info("$prefix main $it") }
                        }
                    }
                }
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, handler)
        handler.post {
            AndroidAgentLogger.info(
                "StreamDiag id=${session.id} start=1 gen=$sessionGeneration intervalMs=5000 " +
                    "frameValue=deadlineMiss histogramMs=16,32,50,100 " +
                    "probeFrames=$TOGGLE_PROBE_FRAMES probeMaxMs=${TOGGLE_PROBE_MAX_NS / 1_000_000} " +
                    "spikeMs=${SPIKE_FRAME_NS / 1_000_000}"
            )
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
            if (mainLog === log) {
                Looper.getMainLooper().setMessageLogging(null)
                mainLog = null
            }
            handler.removeCallbacks(periodic)
            handler.post { emit(true); thread.quitSafely() }
        }
    }
}

/** 被点开那一项持有的当前点击 token；修饰符在测量和绘制时读它，不触发重组。 */
internal class ToggleProbeRef {
    var token = 0
}

/**
 * 记录被点开那一项某一层的高度变化、测量耗时和绘制耗时。只在这一项的点击窗口里记，
 * 其余时间直接透传。放在 AnimatedVisibility 上看到的是动画中的可见高度，放在内容上是内容本身的高度。
 */
internal fun Modifier.toggleProbe(ref: ToggleProbeRef, part: String): Modifier = this
    .layout { measurable, constraints ->
        val token = ref.token
        if (token == 0 || !StreamPerformanceDiagnostics.probing) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val started = System.nanoTime()
        val placeable = measurable.measure(constraints)
        val elapsed = System.nanoTime() - started
        StreamPerformanceDiagnostics.probeEvent(
            token,
            "measure",
            "part=$part h=${placeable.height} us=${elapsed / 1000}",
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    .drawWithContent {
        val token = ref.token
        if (token == 0 || !StreamPerformanceDiagnostics.probing) {
            drawContent()
            return@drawWithContent
        }
        val started = System.nanoTime()
        drawContent()
        val elapsed = System.nanoTime() - started
        StreamPerformanceDiagnostics.probeEvent(
            token,
            "draw",
            "part=$part h=${size.height.toInt()} us=${elapsed / 1000}",
        )
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
