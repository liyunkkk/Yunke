package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import java.util.concurrent.ConcurrentHashMap

/**
 * “每次询问”选定执行位置的收尾：先把选择写进会话，再等弹窗退场，最后（仅前台）补发展示。
 *
 * 返回值始终取自 [AgentRuntimeSession.taskSurfaceMode]，保证工具侧记下的位置与会话一致；
 * 等弹窗退场时被中断不会把已做出的选择变成取消（只恢复中断标志）。
 */
internal object AgentTaskSurfaceChoice {
    fun choose(
        session: AgentRuntimeSession,
        await: () -> AgentTaskSurfaceMode?,
        awaitHostHidden: () -> Unit,
        onForegroundResolved: () -> Unit,
    ): AgentTaskSurfaceMode? {
        val chosen = await()?.takeIf { it != AgentTaskSurfaceMode.ASK } ?: return null
        val resolved = session.resolveTaskSurface(chosen)
        // 并行调用已先定下时以会话为准。
        val mode = session.taskSurfaceMode.takeIf { it != AgentTaskSurfaceMode.ASK } ?: return null
        try {
            awaitHostHidden()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // 等不到弹窗退场也不影响已做出的选择。
        }
        if (resolved && mode == AgentTaskSurfaceMode.FOREGROUND) runCatching(onForegroundResolved)
        return mode
    }
}

/**
 * 记下最近开始的工具。ASK 期间 ToolStarted 已发出但服务没处理（当时还不是前台），
 * 选了前台后据此补一次给服务内部展示用，不进对话记录。
 */
internal class AgentForegroundReplay {
    private val started = ConcurrentHashMap<String, AgentEvent.ToolStarted>()

    @Volatile
    private var round = 0

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.RoundStarted -> round = event.round
            is AgentEvent.ToolStarted -> {
                round = event.round
                started[event.name.trim()] = event
            }
            else -> Unit
        }
    }

    fun startedEvent(toolName: String): AgentEvent.ToolStarted =
        started[toolName.trim()] ?: AgentEvent.ToolStarted(
            round = round,
            toolCallId = "",
            name = toolName,
            argsPreview = "",
        )
}
