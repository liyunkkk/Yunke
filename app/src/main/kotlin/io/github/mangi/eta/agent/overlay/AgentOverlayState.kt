package io.github.mangi.eta.agent.overlay

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.runtime.AgentEvent

/** Agent 浮窗所处的阶段。 */
internal enum class AgentOverlayPhase { RUNNING, PAUSED, FINISHED, FAILED }

/**
 * 悬浮胶囊展开后用于跳转的一条会话：running 表示还在跑，false 表示已完成但仍可点回去。
 */
internal data class AgentOverlayRunningSession(
    val runId: String,
    val conversationId: String,
    val title: String,
    val running: Boolean = true,
)

/**
 * Agent 浮窗的渲染状态。由 [AgentEvent] 流累积而来，胶囊 [AgentOverlayCapsule] 直接消费。
 */
@Immutable
internal data class AgentOverlayState(
    val phase: AgentOverlayPhase = AgentOverlayPhase.RUNNING,
    val round: Int = 0,
    val status: AgentOverlayStatus = AgentOverlayStatus.Preparing,
    val detailText: String = "",
    /**
     * 胶囊第二行：思考时是模型最近一句可见推理。只保留尾部一小段，供用户扫一眼知道
     * Agent 在想什么；完整推理仍在会话里。
     */
    val thought: String = "",
    /** 胶囊展开后的会话列表：运行中的在前，其后是最近完成的（最多 3 条）。 */
    val runningSessions: List<AgentOverlayRunningSession> = emptyList(),
    /** 完成后还没看过的条数：收起态小胶囊右上角用 1、2、3… 显示（上限 9+）。 */
    val finishedBadge: Int = 0,
    /** 最近打开过的对话：面板最上面一栏，最多显示 3 条，多的折叠。 */
    val recentOpenedSessions: List<AgentOverlayRunningSession> = emptyList(),
    /** 完成但还没看过的会话：面板「最新回复」栏，点一条直接跳回去。 */
    val unreadSessions: List<AgentOverlayRunningSession> = emptyList(),
) {
    companion object {
        val Initial = AgentOverlayState(status = AgentOverlayStatus.Received)
    }
}

/** Late model progress must not replace the authoritative paused control state. */
internal fun AgentOverlayState.applyControlledEvent(event: AgentEvent, isPaused: Boolean): AgentOverlayState {
    val projected = applyEvent(event)
    return if (isPaused && event !is AgentEvent.RunFinished && event !is AgentEvent.RunFailed) {
        projected.copy(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
    } else projected
}

/**
 * 将一个 [AgentEvent] 折叠进当前渲染状态。
 *
 * 文案逻辑只保留面向用户的一句话状态，
 * 工具名经 [toToolLabel] 中文化。详细 trace 流作为后续任务，此处不展开。
 */
internal fun AgentOverlayState.applyEvent(event: AgentEvent): AgentOverlayState = when (event) {
    is AgentEvent.RunStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.PreparingTools(event.toolCount),
        detailText = "",
    )

    is AgentEvent.RoundStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ReasoningRound(event.round),
    )

    is AgentEvent.ErrorReconnectChanged -> copy(
        phase = when (event.status) {
            "failed" -> AgentOverlayPhase.FAILED
            "stopped" -> AgentOverlayPhase.FINISHED
            else -> AgentOverlayPhase.RUNNING
        },
        round = event.round,
        status = when (event.status) {
            "failed" -> AgentOverlayStatus.RunFailed
            "stopped" -> AgentOverlayStatus.Stopped
            else -> AgentOverlayStatus.RequestingModel
        },
        detailText = "",
    )

    is AgentEvent.ModelRetryScheduled -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RequestingModel,
        detailText = event.displayMessage,
    )

    is AgentEvent.ProviderRequestStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RequestingModel,
        detailText = "",
    )

    is AgentEvent.ProviderResponseStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ModelResponded,
    )

    is AgentEvent.AssistantBlockStart -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> this
        AgentEvent.AssistantBlockKind.THINKING -> copy(thought = "")

        AgentEvent.AssistantBlockKind.TOOL_CALL -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.GeneratingToolArguments,
        )
    }

    is AgentEvent.AssistantBlockDelta -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> appendStreamingText(event)
        AgentEvent.AssistantBlockKind.THINKING -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.Reasoning,
            thought = appendThought(thought, event.delta),
        )

        AgentEvent.AssistantBlockKind.TOOL_CALL -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            status = AgentOverlayStatus.GeneratingToolArguments,
        )
    }

    is AgentEvent.AssistantBlockEnd -> this

    is AgentEvent.AssistantReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = if (event.toolNames.isEmpty()) AgentOverlayStatus.PreparingAnswer
        else AgentOverlayStatus.PlanningTools(event.toolNames),
    )

    is AgentEvent.UsageReceived -> this

    is AgentEvent.UserSupplementReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.SupplementReceived,
        detailText = "",
    )

    is AgentEvent.QuestionRequested -> copy(phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.WaitingForAnswer, detailText = event.request.title)
    is AgentEvent.QuestionResolved -> copy(phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.Continuing, detailText = "")

    is AgentEvent.ToolStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RunningTool(event.name),
    )

    is AgentEvent.ToolFinished -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ToolCompleted(event.name),
        thought = "",
    )

    is AgentEvent.HostedToolStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.HostedToolRunning(event.name),
    )

    is AgentEvent.HostedToolFinished -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.HostedToolFinished(event.name, event.success),
    )

    is AgentEvent.ToolImagesAttached -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.ImagesRead(event.imageCount),
    )

    is AgentEvent.ChildContextUpdated -> this

    is AgentEvent.AutoCompactWaiting -> this

    is AgentEvent.ContextCompactionStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.RequestingModel,
        detailText = "",
    )

    is AgentEvent.ContextCompacted -> if (event.blocked) copy(
        phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused, detailText = event.reason,
    ) else this

    is AgentEvent.RunFinished -> copy(
        phase = AgentOverlayPhase.FINISHED,
        round = event.round,
        status = AgentOverlayStatus.ResultReady,
    )

    is AgentEvent.RunFailed -> copy(
        phase = AgentOverlayPhase.FAILED,
        status = AgentOverlayStatus.RunFailed,
        detailText = "",
    )
}

private const val MaxStreamingPreviewChars = 320
private const val MaxThoughtChars = 96

/** 合并空白并只保留尾部，胶囊里显示的是"刚刚想到哪"，不是整段推理。 */
private fun appendThought(current: String, delta: String): String {
    val merged = (current + delta).replace(Regex("\\s+"), " ").trimStart()
    return if (merged.length <= MaxThoughtChars) merged else merged.takeLast(MaxThoughtChars).trimStart()
}

private fun AgentOverlayState.appendStreamingText(event: AgentEvent.AssistantBlockDelta): AgentOverlayState {
    val nextPreview = (detailText + event.delta)
        .trimStart()
        .take(MaxStreamingPreviewChars)
    return copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.GeneratingAnswer,
        detailText = nextPreview,
    )
}
