package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import io.github.mangi.eta.R

internal class AgentExecutionNotificationTracker(
    private val context: Context,
    private val handler: Handler,
    private val onStateChanged: (AgentExecutionState) -> Unit,
) {
    private var runStartedAt: Long = 0L
    private var phaseStartedAt: Long = 0L
    private var currentPhase: AgentExecutionPhase = AgentExecutionPhase.IDLE
    private var lastUpdateTime: Long = 0L
    private var pendingUpdateRunnable: Runnable? = null

    private val thinkingBuffer = StringBuilder()
    private val generatingBuffer = StringBuilder()

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 1000L
        private const val MAX_SINGLE_LINE_CHARS = 42
        private const val MAX_EXPANDED_CHARS = 320

        internal fun resolveToolDisplayName(name: String): String = when (name) {
            "observe_screen", "capture_screen" -> "分析屏幕内容"
            "tap", "tap_area", "tap_element" -> "点击屏幕"
            "long_press", "long_press_element" -> "长按屏幕"
            "swipe", "scroll", "scroll_element" -> "滑动屏幕"
            "input_text", "replace_text", "paste_text" -> "输入文本"
            "clear_text" -> "清除输入"
            "press_key" -> "模拟按键"
            "launch_app" -> "启动应用"
            "search_apps" -> "检索应用"
            "terminal", "run_command", "shell", "bash" -> "执行终端命令"
            "read_file" -> "读取文件"
            "write_file", "edit_file" -> "写入文件"
            "read_image" -> "解析图片"
            "web_search", "search" -> "联网检索"
            "get_current_context" -> "获取系统状态"
            "open_uri" -> "打开网页链接"
            "browser_use" -> "自动化浏览器"
            else -> name
        }

        internal fun extractSingleLinePreview(buffer: CharSequence, maxLength: Int = MAX_SINGLE_LINE_CHARS): String {
            val text = buffer.trim().toString()
            if (text.isEmpty()) return ""
            val lastNonEmptyLine = text.lines().lastOrNull { it.isNotBlank() } ?: text
            val clean = lastNonEmptyLine.replace("\n", " ").replace("\r", " ").trim()
            return if (clean.length > maxLength) {
                "…" + clean.takeLast(maxLength)
            } else {
                clean
            }
        }

        internal fun extractTail(buffer: CharSequence, maxLength: Int = MAX_EXPANDED_CHARS): String {
            val text = buffer.trim().toString()
            return if (text.length > maxLength) {
                "…" + text.takeLast(maxLength)
            } else {
                text
            }
        }

        internal fun formatToolPreview(command: String?, argsPreview: String): String {
            val raw = if (!command.isNullOrBlank()) command.trim() else argsPreview.trim()
            if (raw.isBlank()) return ""
            val clean = raw.replace("\n", " ").replace("\r", " ").trim()
            return if (clean.length > MAX_SINGLE_LINE_CHARS) {
                clean.take(MAX_SINGLE_LINE_CHARS) + "…"
            } else {
                clean
            }
        }
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.RunStarted -> {
                runStartedAt = SystemClock.elapsedRealtime()
                phaseStartedAt = runStartedAt
                currentPhase = AgentExecutionPhase.PREPARING
                thinkingBuffer.clear()
                generatingBuffer.clear()
                emitImmediate(
                    AgentExecutionState(
                        phase = AgentExecutionPhase.PREPARING,
                        title = context.getString(R.string.execution_phase_preparing),
                        detail = context.getString(R.string.execution_phase_preparing_detail),
                        startedAtElapsedRealtime = runStartedAt,
                        showChronometer = true,
                    ),
                )
            }
            is AgentEvent.RoundStarted -> {
                if (currentPhase == AgentExecutionPhase.IDLE || currentPhase == AgentExecutionPhase.PREPARING) {
                    phaseStartedAt = SystemClock.elapsedRealtime()
                    currentPhase = AgentExecutionPhase.THINKING
                    emitImmediate(
                        AgentExecutionState(
                            phase = AgentExecutionPhase.THINKING,
                            title = context.getString(R.string.execution_phase_thinking),
                            detail = context.getString(R.string.execution_phase_thinking_detail),
                            startedAtElapsedRealtime = phaseStartedAt,
                            showChronometer = true,
                        ),
                    )
                }
            }
            is AgentEvent.AssistantBlockStart -> {
                phaseStartedAt = SystemClock.elapsedRealtime()
                if (event.kind == AgentEvent.AssistantBlockKind.THINKING) {
                    currentPhase = AgentExecutionPhase.THINKING
                    thinkingBuffer.clear()
                    emitImmediate(
                        AgentExecutionState(
                            phase = AgentExecutionPhase.THINKING,
                            title = context.getString(R.string.execution_phase_thinking),
                            detail = context.getString(R.string.execution_phase_thinking_detail),
                            startedAtElapsedRealtime = phaseStartedAt,
                            showChronometer = true,
                        ),
                    )
                } else if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                    currentPhase = AgentExecutionPhase.GENERATING
                    generatingBuffer.clear()
                    emitImmediate(
                        AgentExecutionState(
                            phase = AgentExecutionPhase.GENERATING,
                            title = context.getString(R.string.execution_phase_generating),
                            detail = context.getString(R.string.execution_phase_generating_detail),
                            startedAtElapsedRealtime = phaseStartedAt,
                            showChronometer = false,
                        ),
                    )
                }
            }
            is AgentEvent.AssistantBlockDelta -> {
                if (event.kind == AgentEvent.AssistantBlockKind.THINKING) {
                    thinkingBuffer.append(event.delta)
                    val preview = extractSingleLinePreview(thinkingBuffer)
                    emitThrottled(
                        AgentExecutionState(
                            phase = AgentExecutionPhase.THINKING,
                            title = context.getString(R.string.execution_phase_thinking),
                            detail = preview.ifBlank { context.getString(R.string.execution_phase_thinking_detail) },
                            startedAtElapsedRealtime = phaseStartedAt,
                            showChronometer = true,
                            expandedSnippet = extractTail(thinkingBuffer),
                        ),
                    )
                } else if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                    generatingBuffer.append(event.delta)
                    val preview = extractSingleLinePreview(generatingBuffer)
                    emitThrottled(
                        AgentExecutionState(
                            phase = AgentExecutionPhase.GENERATING,
                            title = context.getString(R.string.execution_phase_generating),
                            detail = preview.ifBlank { context.getString(R.string.execution_phase_generating_detail) },
                            startedAtElapsedRealtime = phaseStartedAt,
                            showChronometer = false,
                            expandedSnippet = extractTail(generatingBuffer),
                        ),
                    )
                }
            }
            is AgentEvent.ToolStarted -> {
                currentPhase = AgentExecutionPhase.TOOL_EXECUTING
                phaseStartedAt = SystemClock.elapsedRealtime()
                val friendlyName = resolveToolDisplayName(event.name)
                val inputPreview = formatToolPreview(event.command, event.argsPreview)
                val detailText = inputPreview.ifBlank { context.getString(R.string.execution_phase_tool_generic) }
                val expandedText = buildString {
                    append(context.getString(R.string.execution_phase_tool, friendlyName))
                    if (event.command?.isNotBlank() == true) {
                        append("\n$ ").append(event.command.trim())
                    } else if (event.argsPreview.isNotBlank()) {
                        append("\n").append(event.argsPreview.trim())
                    }
                }
                emitImmediate(
                    AgentExecutionState(
                        phase = AgentExecutionPhase.TOOL_EXECUTING,
                        title = context.getString(R.string.execution_phase_tool, friendlyName),
                        detail = detailText,
                        startedAtElapsedRealtime = phaseStartedAt,
                        showChronometer = true,
                        expandedSnippet = expandedText,
                    ),
                )
            }
            is AgentEvent.HostedToolStarted -> {
                currentPhase = AgentExecutionPhase.TOOL_EXECUTING
                phaseStartedAt = SystemClock.elapsedRealtime()
                val friendlyName = resolveToolDisplayName(event.name)
                emitImmediate(
                    AgentExecutionState(
                        phase = AgentExecutionPhase.TOOL_EXECUTING,
                        title = context.getString(R.string.execution_phase_tool, friendlyName),
                        detail = context.getString(R.string.execution_phase_tool_generic),
                        startedAtElapsedRealtime = phaseStartedAt,
                        showChronometer = true,
                        expandedSnippet = context.getString(R.string.execution_phase_tool, friendlyName),
                    ),
                )
            }
            is AgentEvent.RunFinished, is AgentEvent.RunFailed -> {
                reset()
            }
            else -> {}
        }
    }

    private fun emitImmediate(state: AgentExecutionState) {
        cancelPendingUpdate()
        lastUpdateTime = SystemClock.elapsedRealtime()
        onStateChanged(state)
    }

    private fun emitThrottled(state: AgentExecutionState) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastUpdateTime
        if (elapsed >= MIN_UPDATE_INTERVAL_MS) {
            cancelPendingUpdate()
            lastUpdateTime = now
            onStateChanged(state)
        } else if (pendingUpdateRunnable == null) {
            val delay = MIN_UPDATE_INTERVAL_MS - elapsed
            val runnable = Runnable {
                lastUpdateTime = SystemClock.elapsedRealtime()
                pendingUpdateRunnable = null
                onStateChanged(state)
            }
            pendingUpdateRunnable = runnable
            handler.postDelayed(runnable, delay)
        }
    }

    private fun cancelPendingUpdate() {
        pendingUpdateRunnable?.let { handler.removeCallbacks(it) }
        pendingUpdateRunnable = null
    }

    fun reset() {
        cancelPendingUpdate()
        currentPhase = AgentExecutionPhase.IDLE
        thinkingBuffer.clear()
        generatingBuffer.clear()
    }
}
