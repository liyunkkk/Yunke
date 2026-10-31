package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.runtime.AgentEvent

/**
 * Decides when the system-level operation overlay should become visible.
 *
 * Chat, reasoning, shell diagnostics, file reads, skill reads and app search
 * all have good homes in the main conversation UI. The global overlay is
 * reserved for tools that actively inspect or drive the foreground Android
 * interface. Tool names alone cannot distinguish foreground and virtual runs;
 * runtime callers must also pass the originating session's frozen surface mode.
 */
internal object AgentOverlayVisibilityPolicy {
    /**
     * 浮层是否允许显示。
     *
     * 移植 Eta 胶囊后的口径：浮层跟随「是否拥有手机操作权」，与任务落在主屏还是后台虚拟屏无关。
     * 后台虚拟屏恰恰最需要这个胶囊——用户在主屏正常使用，胶囊提供微缩镜与接管入口。
     * 因此三种 surface 都允许；真正决定「要不要画」的是 shouldRevealFor 里的 GUI 事件。
     */
    @Suppress("UNUSED_PARAMETER")
    fun allowsOverlay(taskSurfaceMode: AgentTaskSurfaceMode): Boolean = true

    /** 运行开始时一律先准备好浮层状态；是否显现仍由 GUI 事件驱动。 */
    @Suppress("UNUSED_PARAMETER")
    fun mayUseOverlay(taskSurfaceMode: AgentTaskSurfaceMode): Boolean = true

    fun shouldRevealFor(event: AgentEvent, taskSurfaceMode: AgentTaskSurfaceMode): Boolean =
        allowsOverlay(taskSurfaceMode) && shouldRevealFor(event)

    fun shouldDismissEntrySurfaceFor(event: AgentEvent, taskSurfaceMode: AgentTaskSurfaceMode): Boolean =
        allowsOverlay(taskSurfaceMode) && shouldDismissEntrySurfaceFor(event)

    fun shouldShowResultCard(
        taskSurfaceMode: AgentTaskSurfaceMode,
        hasExecutedForegroundTool: Boolean,
    ): Boolean = allowsOverlay(taskSurfaceMode) && hasExecutedForegroundTool

    fun shouldRevealFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantBlockStart ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.isForegroundDrivingTool()
        is AgentEvent.AssistantBlockEnd ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.isForegroundDrivingTool()
        is AgentEvent.AssistantReceived -> event.toolNames.any { it.isForegroundDrivingTool() }
        is AgentEvent.ToolStarted -> event.name.isForegroundDrivingTool()
        is AgentEvent.ToolFinished -> event.name.isForegroundOperationTool()
        is AgentEvent.ToolImagesAttached -> event.toolName.isForegroundOperationTool()
        else -> false
    }

    fun shouldDismissEntrySurfaceFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantBlockStart ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.AssistantBlockEnd ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.AssistantReceived -> event.toolNames.any { it.requiresEntrySurfaceDismissal() }
        is AgentEvent.ToolStarted -> event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.ToolFinished -> event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.ToolImagesAttached -> event.toolName.requiresEntrySurfaceDismissal()
        else -> false
    }

    internal fun isForegroundOperationTool(name: String?): Boolean =
        name.isForegroundOperationTool()

    internal fun requiresEntrySurfaceDismissal(name: String?): Boolean =
        name.requiresEntrySurfaceDismissal()

    internal fun shouldRecordForegroundExecution(
        event: AgentEvent,
        entrySurfaceReady: Boolean,
        taskSurfaceMode: AgentTaskSurfaceMode = AgentTaskSurfaceMode.FOREGROUND,
    ): Boolean =
        allowsOverlay(taskSurfaceMode) &&
            entrySurfaceReady &&
            event is AgentEvent.ToolStarted &&
            event.name.isForegroundOperationTool()

    private fun String?.isForegroundOperationTool(): Boolean =
        this?.trim()?.lowercase() in foregroundOperationTools

    private fun String?.isForegroundDrivingTool(): Boolean =
        this?.trim()?.lowercase() in foregroundDrivingTools

    private fun String?.requiresEntrySurfaceDismissal(): Boolean =
        this?.trim()?.lowercase() in entrySurfaceDismissalTools

    private val foregroundDrivingTools = setOf(
        // 副屏会话生命周期：建屏/收屏本身就是「开始占用手机」的信号，要显现胶囊。
        "start_virtual_session",
        "keep_virtual_result",
        "finish_virtual_session",
        "present_to_user",
        "launch_app",
        "open_uri",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "input_text",
        "replace_text",
        "clear_text",
        "paste_text",
        "press_key",
        "open_system_panel",
    )

    private val foregroundOperationTools = setOf(
        "observe_screen",
        *foregroundDrivingTools.toTypedArray(),
    )

    private val entrySurfaceDismissalTools =
        foregroundOperationTools + setOf("set_alarm", "set_timer")
}
