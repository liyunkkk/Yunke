package io.github.mangi.eta.ui.components

import java.security.MessageDigest
import java.security.SecureRandom
import java.lang.ref.WeakReference

/** All collector/trace labels are compiled-in, not inferred from payloads or class names. */
internal object StreamDiagnosticLabels {
    val stages: Set<String> = setOf(
        "ui.enqueue", "ui.runEvent", "ui.replay", "ui.flush", "ui.flushDelay", "ui.delta.received", "ui.delta.gap",
        "ui.flush.blockSwitch", "ui.flush.nonDelta", "ui.flush.timer",
        "ui.messages.apply", "ui.messages.transform", "ui.messages.normalize", "ui.messages.publish",
        "ui.conversation.route", "ui.conversation.owner", "ui.conversation.waiting", "ui.conversation.publish", "ui.summaries.refresh",
        "ipc.delta.delay", "ipc.client.receive", "ipc.attach.receive",
        "ipc.client.decode.live", "ipc.client.callback.live", "ipc.attach.decode.live", "ipc.attach.decode.replay",
        "ipc.attach.callback.live", "ipc.attach.callback.replay", "ipc.attach.callback.replayBatch",
        "runtime.checkpoint.accept", "runtime.checkpoint.append", "runtime.checkpoint.flush.size", "runtime.checkpoint.flush.timer",
        "runtime.checkpoint.flush.boundary", "runtime.checkpoint.flush.seal",
        "chat.compose", "timeline.project", "timeline.prefaces", "gallery.scan", "gallery.parse", "gallery.hit", "gallery.skip",
        "markdown.target", "markdown.coalesced", "markdown.queueWait", "markdown.parse", "markdown.superseded",
        "markdown.publishBlock", "markdown.targetToPublish", "markdown.publish", "markdown.layout", "markdown.blockDraw",
        "reveal.frameGap", "reveal.step", "reveal.backlog", "reveal.remeasure",
        "scroll.state", "tail.clippedPx", "tail.breachPx", "follow.initialSnap", "follow.decision", "follow.frameGap",
        "follow.scroll", "follow.step", "follow.cancelled", "follow.viewportRecovery",
        "heap.usedBytes", "main.frameMessages", "main.otherMessages", "main.message", "main.doFrame",
        "frame.total", "frame.layout", "frame.draw", "frame.sync", "frame.gpu", "frame.input", "frame.unknown",
        "frame.animation", "frame.command", "frame.swap", "frame.unaccounted", "frame.overlap", "frame.vsyncLate",
        "frame.metricsDropped", "frame.deadline",
        "persistence.write", "persistence.read", "persistence.serialize", "persistence.queueWait",
        "render.measure", "render.draw", "render.compose",
    )
    val kinds: Set<String> = setOf(
        "unknown", "replayBatch", "start.text", "start.thinking", "start.toolCall", "delta.text", "delta.thinking", "delta.toolCall",
        "end.text", "end.thinking", "end.toolCall", "runStarted", "roundStarted", "modelRetry", "reconnect", "providerRequest",
        "providerResponse", "assistantReceived", "childContext", "usage.projected", "usage.receipt", "supplement", "toolStarted",
        "toolFinished", "hostedToolStarted", "hostedToolFinished", "toolImages", "compactWaiting", "compactStarted", "compacted",
        "runFinished", "runFailed", "questionRequested", "questionResolved",
    )
    fun kind(label: String): String = if (label in kinds) label else "unknown"
    private val families = setOf("render", "markdown", "reveal", "settings", "persistence", "usage", "datastore", "citation")
    /** Unknown suffixes collapse to a fixed label; they are NEVER retained verbatim. */
    fun canonicalStage(label: String): String? {
        if (stage(label)) return label
        val family = label.substringBefore('.')
        if (family in families) return "$family.unknown"
        if (label.startsWith("runtime.checkpoint.")) return "runtime.checkpoint.unknown"
        return null
    }
    fun stage(label: String): Boolean = label in stages ||
        (label.startsWith("ui.event.") && label.removePrefix("ui.event.") in kinds) ||
        label in pageStages
    // Keep this pure Kotlin: compiled static route names, never arbitrary frame.page.* input.
    private val pageStages = ("Unknown Transition BrowserOverlay ConversationDrawer Home Chat Browser Terminal Tools " +
        "AgentTaskPreference VirtualDisplayRecovery Haptics Skills Permissions SystemEnhance Settings SpeechSettings TtsSettings " +
        "VoiceSettings AuxiliaryVision TitleModel ErrorReconnectSettings SubAgents VoiceModeSettings AppearanceSettings DataBackup " +
        "Memory LinuxEnvironment SharedFolders Workspace LinuxFiles ModelProviders McpServers ModelProviderDetail " +
        "ModelProviderAuthMethod ModelProviderNew ContextCompression Assistants AssistantEdit UsageStats ManageChats McpServerDetail")
        .split(' ').map { "frame.page.$it" }.toSet()
}

/** Pure Kotlin, process/session-local pseudonyms. Only salted fingerprints are retained. */
internal class AnonymousDiagnosticTokens(private val capacity: Int = 256) {
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val fingerprints = arrayOfNulls<ByteArray>(capacity)
    // Bounded in-memory cache; never traversed by a formatter or exposed in snapshots.
    private val identities = arrayOfNulls<String>(capacity)
    private val digest = MessageDigest.getInstance("SHA-256")
    private var size = 0
    var saturated = 0L
        private set
    init { require(capacity > 0) }

    @Synchronized fun token(identity: String?): Int {
        if (identity == null) return 0
        for (i in 0 until size) if (identities[i] == identity) return i + 1
        if (size == capacity) { saturated++; return 0 }
        digest.update(salt)
        val fingerprint = digest.digest(identity.toByteArray(Charsets.UTF_8))
        for (i in 0 until size) if (fingerprints[i]!!.contentEquals(fingerprint)) return i + 1
        fingerprints[size] = fingerprint
        identities[size] = identity
        return ++size
    }
}

internal enum class DiagnosticVisibility { Unknown, Selected, Hidden }

/** No business identity or payload. Safe to explicitly pass to existing background work. */
internal data class StreamDiagnosticAttribution(
    val session: Long,
    val run: Int = 0,
    val conversation: Int = 0,
    val visibility: DiagnosticVisibility = DiagnosticVisibility.Unknown,
    val kind: String = "unknown",
    val replay: Boolean = false,
    val event: Long = 0,
    val sourceSpan: Long = 0,
)

internal data class DiagnosticSpanContext(
    val attribution: StreamDiagnosticAttribution?,
    val span: Long = 0,
)

/** Synchronous scopes only: never leave this installed across a coroutine suspension. */
internal class DiagnosticThreadContext {
    private val local = ThreadLocal<DiagnosticSpanContext?>()
    fun current(): DiagnosticSpanContext? = local.get()
    fun capture(): StreamDiagnosticAttribution? = current()?.let { context ->
        context.attribution?.copy(sourceSpan = context.span.takeIf { it != 0L } ?: context.attribution.sourceSpan)
    }
    fun <T> with(context: DiagnosticSpanContext?, block: () -> T): T {
        val previous = local.get()
        local.set(context)
        try { return block() } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }
}

internal data class DiagnosticSpanRecord(
    val stage: String, val span: Long, val parent: Long,
    val beginNs: Long, val endNs: Long, val thread: Long, val main: Boolean,
    val attribution: StreamDiagnosticAttribution?, val page: Int, val pageEnd: Int, val value: Long,
)

internal data class DiagnosticFrameRecord(
    val intendedNs: Long, val vsyncNs: Long, val totalNs: Long, val deadlineNs: Long,
    val page: Int, val pageEnd: Int, val changed: Boolean, val metricsDropped: Int,
    val unknownNs: Long, val inputNs: Long, val animationNs: Long, val layoutNs: Long,
    val drawNs: Long, val syncNs: Long, val commandNs: Long, val swapNs: Long, val gpuNs: Long,
) {
    val missed: Boolean get() = deadlineNs > 0 && totalNs > deadlineNs
}

internal data class DiagnosticDetailSnapshot(
    val fromNs: Long, val toNs: Long,
    val spans: List<DiagnosticSpanRecord>, val frames: List<DiagnosticFrameRecord>,
    val overwritten: Long, val slowBudgetDropped: Long, val frameBudgetDropped: Long,
    val spanOutputTruncated: Long,
)

/**
 * Preallocated ring: hot paths write primitive columns, no Runnable or formatted line per span.
 * Slow records reserve their budget before insertion; frames likewise before any formatting.
 * Windows are [from,to); a completed span belongs to its completion window. Long spans can
 * begin before that window. Frame timestamps can precede delivery; they are not relabelled.
 */
internal class BoundedDiagnosticDetails(
    startedNs: Long,
    private val capacity: Int = 2048,
    private val slowLimit: Int = 256,
    private val frameLimit: Int = 120,
    private val slowNs: Long = 4_000_000L,
) {
    private val stages = arrayOfNulls<String>(capacity)
    private val spans = LongArray(capacity)
    private val parents = LongArray(capacity)
    private val begins = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val threads = LongArray(capacity)
    private val values = LongArray(capacity)
    private val mains = BooleanArray(capacity)
    private val attrs = arrayOfNulls<StreamDiagnosticAttribution>(capacity)
    private val pages = IntArray(capacity)
    private val pageEnds = IntArray(capacity)
    private var next = 0
    private var size = 0
    private var fromNs = startedNs
    private var overwritten = 0L
    private var slowAccepted = 0
    private var slowDropped = 0L
    private var frameDropped = 0L
    // Fixed-size, bounded objects on the worker only (FrameMetrics callback).
    private val frames = arrayOfNulls<DiagnosticFrameRecord>(frameLimit)
    private var frameSize = 0
    init { require(capacity > 0 && slowLimit > 0 && frameLimit > 0) }

    @Synchronized fun span(
        stage: String, span: Long, parent: Long, beginNs: Long, endNs: Long,
        thread: Long, main: Boolean, attribution: StreamDiagnosticAttribution?, page: Int, pageEnd: Int, value: Long = 0,
    ) {
        require(endNs >= beginNs)
        if (endNs - beginNs >= slowNs) {
            if (slowAccepted >= slowLimit) { slowDropped++; return }
            slowAccepted++
        }
        if (size == capacity) overwritten++ else size++
        stages[next] = stage; spans[next] = span; parents[next] = parent
        begins[next] = beginNs; ends[next] = endNs; threads[next] = thread; mains[next] = main
        attrs[next] = attribution; pages[next] = page; pageEnds[next] = pageEnd; values[next] = value
        next = (next + 1) % capacity
    }

    /** Called before building a detailed frame object or a string. */
    @Synchronized fun reserveFrame(): Boolean {
        if (frameSize >= frameLimit) { frameDropped++; return false }
        // Caller is the one diagnostic worker; reservation and insertion cannot interleave.
        return true
    }
    @Synchronized fun frame(frame: DiagnosticFrameRecord) {
        if (frameSize >= frameLimit) { frameDropped++; return }
        frames[frameSize++] = frame
    }

    @Synchronized fun drain(toNs: Long): DiagnosticDetailSnapshot {
        require(toNs >= fromNs)
        val frameSnapshot = (0 until frameSize).map { frames[it]!! }
        val out = ArrayList<DiagnosticSpanRecord>(minOf(size, slowLimit))
        var truncated = 0L
        val first = (next - size + capacity) % capacity
        for (i in 0 until size) {
            val slot = (first + i) % capacity
            val slow = ends[slot] - begins[slot] >= slowNs
            val overlapsFrame = mains[slot] && frameSnapshot.any {
                begins[slot] < it.intendedNs + it.totalNs && ends[slot] > it.intendedNs
            }
            if (slow || overlapsFrame) {
                if (out.size == slowLimit) truncated++ else out += DiagnosticSpanRecord(
                    stages[slot]!!, spans[slot], parents[slot], begins[slot], ends[slot], threads[slot],
                    mains[slot], attrs[slot], pages[slot], pageEnds[slot], values[slot],
                )
            }
            stages[slot] = null; attrs[slot] = null
        }
        val snapshot = DiagnosticDetailSnapshot(fromNs, toNs, out, frameSnapshot,
            overwritten, slowDropped, frameDropped, truncated)
        for (i in 0 until frameSize) frames[i] = null
        fromNs = toNs; size = 0; next = 0; overwritten = 0
        slowAccepted = 0; slowDropped = 0; frameDropped = 0; frameSize = 0
        return snapshot
    }
}

/** Bounded identity hand-off for replay; saturation is explicit, never holds an entire history. */
internal class DiagnosticEventLinks(private val capacity: Int = 512) {
    private val objects = arrayOfNulls<WeakReference<Any>>(capacity)
    private val attrs = arrayOfNulls<StreamDiagnosticAttribution>(capacity)
    private var next = 0
    private var size = 0
    var overwritten = 0L
        private set
    @Synchronized fun bind(event: Any, attribution: StreamDiagnosticAttribution) {
        for (i in objects.indices) if (objects[i]?.get() === event) { attrs[i] = attribution; return }
        if (size == capacity) overwritten++ else size++
        objects[next] = WeakReference(event); attrs[next] = attribution
        next = (next + 1) % capacity
    }
    @Synchronized fun find(event: Any): StreamDiagnosticAttribution? {
        for (i in objects.indices) if (objects[i]?.get() === event) return attrs[i]
        return null
    }
}
