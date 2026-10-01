package io.github.mangi.eta.agent.device

import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AgentTaskSurfaceMode(val wire: String, val labelRes: Int) {
    ASK("ask", R.string.agent_task_surface_ask),
    FOREGROUND("foreground", R.string.agent_task_surface_foreground),
    BACKGROUND("background", R.string.agent_task_surface_background);

    companion object {
        fun fromWire(value: String?): AgentTaskSurfaceMode =
            entries.firstOrNull { it.wire == value } ?: FOREGROUND

        /**
         * 模块是否安装都不能改写存储值。
         * 缺模块时把 BACKGROUND/ASK 收成 FOREGROUND 会静默改走前台；
         * 模块在场也不表示本阶段的副屏交接已经可用。
         */
        fun resolve(
            stored: AgentTaskSurfaceMode,
            @Suppress("UNUSED_PARAMETER") moduleInstalled: Boolean,
        ): AgentTaskSurfaceMode = stored
    }
}

/**
 * 前台直接在当前屏幕执行，后台走副屏。
 * ASK 在第一次界面操作前弹窗，由用户为本次 run 选定前台或后台；选定前界面工具一律拦下。
 * 设置入口仅在后端模块已安装时可见；不改变已保存的执行模式。
 */
internal object AgentTaskSurface {
    const val PREF_KEY = "agent_task_surface"

    private val traditionalScreenGuiTools: Set<String> = setOf(
        "observe_screen",
        "wait",
        "wait_for_text",
        "wait_for_package",
        "input_text",
        "replace_text",
        "clear_text",
        "paste_text",
        "press_key",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "open_system_panel",
        "launch_app",
        "open_uri",
        // 直达失败时可能只打开时钟界面。
        "set_alarm",
        "set_timer",
    )

    fun moduleInstalled(): Boolean {
        val binary = File("/system/bin/vd")
        if (binary.isFile && binary.canExecute()) return true
        return File("/data/adb/modules/agent_mobile_use/module.prop").isFile
    }

    fun stored(): AgentTaskSurfaceMode =
        AgentTaskSurfaceMode.fromWire(Prefs.getString(PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire))

    fun allowsPersist(@Suppress("UNUSED_PARAMETER") mode: AgentTaskSurfaceMode): Boolean = true

    fun save(mode: AgentTaskSurfaceMode) {
        if (!allowsPersist(mode)) throw VirtualDisplayHandoffNotReadyException()
        Prefs.putString(PREF_KEY, mode.wire)
    }

    fun effective(): AgentTaskSurfaceMode = AgentTaskSurfaceMode.resolve(stored(), moduleInstalled())

    fun settingsEntryVisible(): Boolean = settingsEntryVisible(moduleInstalled(), stored())

    fun settingsEntryVisible(moduleInstalled: Boolean, @Suppress("UNUSED_PARAMETER") stored: AgentTaskSurfaceMode): Boolean =
        moduleInstalled

    fun settingsSummaryRes(stored: AgentTaskSurfaceMode): Int = when (stored) {
        AgentTaskSurfaceMode.FOREGROUND -> stored.labelRes
        AgentTaskSurfaceMode.BACKGROUND -> R.string.agent_task_surface_background_summary
        AgentTaskSurfaceMode.ASK -> R.string.agent_task_surface_ask_summary
    }

    /** 本次 run 是否还需要先问用户：只有界面与副屏生命周期工具才需要定下执行位置。 */
    fun needsSurfaceChoice(toolName: String): Boolean =
        isTraditionalScreenGuiTool(toolName) || toolName.trim() in virtualLifecycleTools

    private val virtualLifecycleTools = setOf("start_virtual_session", "keep_virtual_result", "finish_virtual_session")

    fun handoffPromptClause(): String {
        val storedMode = runCatching { stored() }.getOrDefault(AgentTaskSurfaceMode.BACKGROUND)
        val installed = runCatching { moduleInstalled() }.getOrDefault(false)
        return handoffPromptClause(moduleInstalled = installed, stored = storedMode)
    }

    fun handoffPromptClause(
        @Suppress("UNUSED_PARAMETER") moduleInstalled: Boolean,
        stored: AgentTaskSurfaceMode,
    ): String = when (stored) {
        AgentTaskSurfaceMode.FOREGROUND -> ""
        AgentTaskSurfaceMode.BACKGROUND -> BACKGROUND_CLAUSE
        AgentTaskSurfaceMode.ASK ->
            "本次执行位置为“每次询问”：第一次调用屏幕或应用操作工具（含 launch_app、observe_screen、tap 与副屏会话工具）时，" +
                "手机会弹窗请用户选择前台或后台；工具会等用户选完才返回，不要因为等待而重复调用。" +
                "结果里的 task_surface=foreground 表示之后都在主屏直接操作；" +
                "task_surface=background 表示之后都在后台副屏操作，并遵守：" + BACKGROUND_CLAUSE +
                "返回 TASK_SURFACE_CANCELLED 表示用户取消了这次操作，不要再调用界面工具，改用其他方式或说明情况。"
    }

    private const val BACKGROUND_CLAUSE =
            "本次选择实验性后台副屏。GUI 不得回退主屏；先 launch_app 精确包名、observe_screen 截图再坐标操作。节点、系统面板及不支持的工具会明确拒绝。要切回本次副屏已打开的应用，直接对同一包名再次 launch_app（返回 reused=true），不要强制停止应用。任务完成前用 keep_virtual_result 标记交付任务，再 finish_virtual_session，只有返回 handedOff=true 且 released=true 才可声称交付完成。没有可交付结果时也要调用 finish_virtual_session，它会清理本次中间任务并关闭副屏（handedOff=false）。收尾失败保留副屏，禁止杀进程或用终端绕过关闭。提示用户期间不要从桌面启动或清理正在操作的应用。"

    fun useVirtualDisplay(): Boolean = useVirtualDisplay(stored())

    fun useVirtualDisplay(stored: AgentTaskSurfaceMode): Boolean = when (stored) {
        AgentTaskSurfaceMode.FOREGROUND -> false
        AgentTaskSurfaceMode.BACKGROUND -> true
        AgentTaskSurfaceMode.ASK -> throw VirtualDisplayHandoffNotReadyException()
    }

    /**
     * 非 GUI 工具立即返回，不读取 Prefs。
     * GUI 工具读取失败时拒绝，避免异常被当成前台放行。
     */
    fun blocksGuiTool(toolName: String): Boolean = blocksGuiTool(toolName, readMode = { stored() })

    fun blocksGuiTool(toolName: String, mode: AgentTaskSurfaceMode): Boolean =
        mode != AgentTaskSurfaceMode.FOREGROUND && isTraditionalScreenGuiTool(toolName)

    fun blocksGuiTool(toolName: String, readMode: () -> AgentTaskSurfaceMode): Boolean {
        if (!isTraditionalScreenGuiTool(toolName)) return false
        val mode = runCatching(readMode).getOrNull() ?: return true
        return blocksGuiTool(toolName, mode)
    }

    fun isTraditionalScreenGuiTool(toolName: String): Boolean =
        toolName.trim() in traditionalScreenGuiTools
}

/**
 * “每次询问”的待决选择。
 *
 * 工具线程在 [await] 里等用户选完；界面（弹窗 Activity 或应用内弹窗）读 [pending] 显示，
 * 再用 [answer] 回答。每个请求只对应一次 run，取消 run 时等待会立刻结束并返回 null。
 */
internal object AgentTaskPrompt {
    data class Request(val id: String, val runId: String)

    private val lock = Any()
    private val queue = LinkedHashMap<String, Pair<Request, CompletableFuture<AgentTaskSurfaceMode?>>>()
    private val pendingState = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = pendingState.asStateFlow()

    private val hosts = AtomicInteger(0)
    private val hostVisibleState = MutableStateFlow(false)
    /** 独立弹窗 Activity 正在显示；此时应用内弹窗不再重复显示。 */
    val hostVisible: StateFlow<Boolean> = hostVisibleState.asStateFlow()

    /**
     * 阻塞当前工具线程直到用户选择。返回 null 表示取消（用户取消、run 已停止）。
     * [show] 在请求入队后调用，用来拉起弹窗；失败不影响应用内弹窗。
     */
    fun await(
        runId: String,
        cancelled: () -> Boolean,
        show: (Request) -> Unit = {},
        pollMillis: Long = POLL_MILLIS,
    ): AgentTaskSurfaceMode? {
        val request = Request(UUID.randomUUID().toString(), runId)
        val answer = CompletableFuture<AgentTaskSurfaceMode?>()
        synchronized(lock) {
            queue[request.id] = request to answer
            publish()
        }
        try {
            runCatching { show(request) }
            while (true) {
                if (cancelled()) return null
                val chosen = try {
                    answer.get(pollMillis, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    continue
                }
                return chosen?.takeIf { it != AgentTaskSurfaceMode.ASK }
            }
        } finally {
            synchronized(lock) {
                queue.remove(request.id)
                publish()
            }
        }
    }

    fun answer(requestId: String, mode: AgentTaskSurfaceMode?) {
        synchronized(lock) { queue[requestId]?.second }?.complete(mode)
    }

    /** 选完后等弹窗窗口退场，避免前台截图或点击落在弹窗上。 */
    fun awaitHostHidden(timeoutMillis: Long = HOST_HIDE_TIMEOUT_MILLIS) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (hosts.get() > 0 && System.nanoTime() < deadline) Thread.sleep(25)
    }

    fun hostStarted() {
        hostVisibleState.value = hosts.incrementAndGet() > 0
    }

    fun hostStopped() {
        hostVisibleState.value = hosts.updateAndGet { (it - 1).coerceAtLeast(0) } > 0
    }

    private fun publish() {
        pendingState.value = queue.values.firstOrNull()?.first
    }

    private const val POLL_MILLIS = 250L
    private const val HOST_HIDE_TIMEOUT_MILLIS = 1_500L
}
