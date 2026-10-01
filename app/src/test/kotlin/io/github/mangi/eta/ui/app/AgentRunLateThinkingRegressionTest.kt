package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * provider 异常：工具之后的新 round 先推完正文，才补上这一轮的 reasoning。
 * 这种迟到 reasoning 不能再新建 thinking block，否则会渲染成“模型回答后继续思考”。
 * 只有“同 round 已有非空正文、且尚无 thinking block”才被拦下；已有 block 与正常多轮
 * reasoning 不受影响。
 */
class AgentRunLateThinkingRegressionTest {
    private val runId = "late-run"

    @Test
    fun reasoningDeltaAfterFinalTextDoesNotStartANewThinkingBlock() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "写一段总结"),
        )
        messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "这是最终正文。", messages)
        messages = projector.finalizeTextRound(runId, 2, messages)
        val afterAnswer = messages

        val late = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "其实还要再想想", messages)

        assertEquals(afterAnswer, late)
        assertEquals(listOf("user-$runId", "assistant-$runId-2-0"), late.map { it.id })
        assertTrue(late.none { it is ThinkingMessageUi })
    }

    @Test
    fun reasoningDeltaAfterStreamingTextDoesNotStartANewThinkingBlock() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "写一段总结"),
        )
        // 正文仍在流式输出：迟到 reasoning 同样不能再新建卡片。
        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "正文先到", messages)
        val afterStreamingText = messages

        val late = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "补推理", messages)

        assertEquals(afterStreamingText, late)
        assertTrue(late.none { it is ThinkingMessageUi })
    }

    @Test
    fun assistantBlockStartThinkingAfterFinalTextIsIgnored() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "总结"),
        )
        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "正文先到。", messages)
        val afterAnswer = messages

        val late = projector.startAssistantBlock(
            runId,
            AgentEvent.AssistantBlockStart(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 1,
            ),
            messages,
        )

        assertEquals(afterAnswer, late)
        assertTrue(late.none { it is ThinkingMessageUi })
    }

    @Test
    fun existingThinkingBlockStillFinalizesAndAcceptsDeltas() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "思考后回答"),
        )
        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "先想清楚", messages)
        messages = projector.finalizeThinkingRound(runId, 1, messages)
        messages = projector.appendTextDelta(runId, round = 1, index = 1, delta = "回答。", messages)
        val afterAnswer = messages

        // 已有 thinking block 仍可继续增量并 finalize，不会被迟到检查吞掉。
        val continued = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "补充", messages)
        val thinking = continued.filterIsInstance<ThinkingMessageUi>().single()
        assertEquals("先想清楚补充", thinking.content)
        assertEquals(1, continued.filterIsInstance<ThinkingMessageUi>().size)
        assertEquals(afterAnswer.size, continued.size)

        val finalized = projector.finalizeThinkingRound(runId, 1, continued)
        assertFalse(finalized.filterIsInstance<ThinkingMessageUi>().single().isStreaming)
    }

    @Test
    fun reasoningAfterToolWithinTheSameRoundIsStillCreated() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "搜索"),
        )
        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "先想", messages)
        messages = projector.appendTextDelta(runId, round = 1, index = 1, delta = "我先查一下。", messages)
        messages = projector.finalizeTextRound(runId, 1, messages)
        messages = projector.startHostedTool(
            runId,
            AgentEvent.HostedToolStarted(round = 1, toolCallId = "ws_1", name = "网页搜索"),
            messages,
        )
        messages = projector.finishHostedTool(
            runId,
            AgentEvent.HostedToolFinished(round = 1, toolCallId = "ws_1", name = "网页搜索", success = true),
            messages,
        )
        // 同 round 已有 thinking block：工具后的 reasoning 属于正常多轮流程，必须保留。
        messages = projector.appendReasoningDelta(runId, round = 1, index = 2, delta = "整理搜索结果", messages)

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "assistant-$runId-1-1",
                "$runId-tool-1-ws_1",
                "$runId-thinking-1-2",
            ),
            messages.map { it.id },
        )
    }

    @Test
    fun firstReasoningAfterHostedToolIsAllowedWithoutEarlierThinking() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi("user-$runId", "搜索"))
        messages = projector.appendTextDelta(runId, 1, 0, "先查资料。", messages)
        messages = projector.startHostedTool(
            runId, AgentEvent.HostedToolStarted(1, "ws_1", "网页搜索"), messages,
        )
        messages = projector.finishHostedTool(
            runId, AgentEvent.HostedToolFinished(1, "ws_1", "网页搜索", true), messages,
        )
        messages = projector.startAssistantBlock(
            runId, AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 1), messages,
        )
        messages = projector.appendReasoningDelta(runId, 1, 1, "整理资料", messages)
        assertEquals("整理资料", messages.filterIsInstance<ThinkingMessageUi>().single().content)
        assertTrue(messages.indexOfFirst { it is ThinkingMessageUi } >
            messages.indexOfFirst { it.id == "$runId-tool-1-ws_1" })
    }

    @Test
    fun normalReasoningInALaterRoundWithoutTextIsStillCreated() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "任务"),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "第一轮正文", isStreaming = false),
        )

        // 第二 round 还没有正文：正常 reasoning 照常展开。
        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "继续推理", messages)

        assertEquals(
            listOf("user-$runId", "assistant-$runId-1-0", "$runId-thinking-2-0"),
            messages.map { it.id },
        )
    }

    @Test
    fun ensureCompletedThinkingKeepsReasoningAheadOfTheFinalText() {
        val projector = AgentRunMessageProjector { 1_000L }
        val user = UserMessageUi(id = "user-$runId", content = "任务")
        val finalText = AgentMessageUi(id = "assistant-$runId-3-0", content = "最终正文", isStreaming = false)

        // 非流式 reasoning 走 backfill 通道，总是插在最终正文之前，不能被丢弃。
        val messages = projector.ensureCompletedThinking(
            runId = runId,
            round = 3,
            content = "补全的推理",
            messages = listOf(user, finalText),
        )

        assertEquals(
            listOf("user-$runId", "$runId-thinking-3-fallback", "assistant-$runId-3-0"),
            messages.map { it.id },
        )
        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        assertFalse(thinking.isStreaming)
        assertTrue(thinking.collapsed)
    }
}
