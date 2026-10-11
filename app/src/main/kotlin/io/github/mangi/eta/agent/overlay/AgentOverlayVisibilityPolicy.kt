package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.runtime.AgentEvent

/**
 * Decides when the system-level operation overlay should become visible.
 *
 * 两类可见性要分开：
 * - 胶囊窗口本体：只要有任务在跑（前台或后台副屏）就常驻，作为跨会话总控。
 *   由 [allowsCapsule] 判定，副屏（BACKGROUND）同样允许。
 * - 前台操作高亮 / 入口界面消隐 / 结果卡：只在前台模式、且正在驱动前台界面的工具
 *   （点击/滑动/输入等）时才触发，避免后台副屏运行时去抢主屏。由 [allowsForegroundReveal]
 *   判定，仅 FOREGROUND。
 *
 * Chat, reasoning, shell diagnostics, file reads, skill reads and app search
 * all have good homes in the main conversation UI. Tool names alone cannot
 * distinguish foreground and virtual runs; runtime callers must also pass the
 * originating session's frozen surface mode.
 */
internal object AgentOverlayVisibilityPolicy {
    /** 胶囊窗口本体是否允许存在：前台与后台副屏都允许，只有显式关闭浮层才不显示。 */
    fun allowsCapsule(taskSurfaceMode: AgentTaskSurfaceMode): Boolean = true

    /** 前台操作高亮 / 结果卡 / 入口界面消隐是否允许：仅前台模式。 */
    fun allowsForegroundReveal(taskSurfaceMode: AgentTaskSurfaceMode): Boolean =
        taskSurfaceMode == AgentTaskSurfaceMode.FOREGROUND

    /** 兼容旧调用：等价于 [allowsForegroundReveal]。 */
    fun allowsOverlay(taskSurfaceMode: AgentTaskSurfaceMode): Boolean =
        allowsForegroundReveal(taskSurfaceMode)

    /** 运行开始时：任何模式都先准备好胶囊状态（副屏也要常驻）。 */
    fun mayUseOverlay(@Suppress("UNUSED_PARAMETER") taskSurfaceMode: AgentTaskSurfaceMode): Boolean = true

    fun shouldRevealFor(event: AgentEvent, taskSurfaceMode: AgentTaskSurfaceMode): Boolean =
        allowsForegroundReveal(taskSurfaceMode) && shouldRevealFor(event)

    fun shouldDismissEntrySurfaceFor(event: AgentEvent, taskSurfaceMode: AgentTaskSurfaceMode): Boolean =
        allowsForegroundReveal(taskSurfaceMode) && shouldDismissEntrySurfaceFor(event)

    fun shouldShowResultCard(
        taskSurfaceMode: AgentTaskSurfaceMode,
        hasExecutedForegroundTool: Boolean,
    ): Boolean = allowsForegroundReveal(taskSurfaceMode) && hasExecutedForegroundTool

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
        allowsForegroundReveal(taskSurfaceMode) &&
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
