package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Block identity and subsequent tool evidence, never reasoning-content prefixes, govern admission. */
class AgentRunPendingThinkingRegressionTest {
    private val runId = "pending-run"

    @Test
    fun commentaryThenNewReasoningIsFlushedBeforeTheSubsequentTool() {
        // Reproduced boundary 1: Text(0), Reasoning(1), ToolStarted.
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "我先查一下", emptyList())
        val commentary = messages
        messages = startThinking(projector, messages, index = 1)
        messages = projector.appendReasoningDelta(runId, 1, 1, "选择检索词", messages)
        assertEquals(commentary, messages)

        messages = projector.startTool(
            runId, AgentEvent.ToolStarted(1, "local", "search", "{}"), messages,
        )

        assertEquals(
            listOf("assistant-$runId-1-0", "$runId-thinking-1-1", "$runId-tool-1-local"),
            messages.map { it.id },
        )
        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        assertEquals("选择检索词", thinking.content)
        assertFalse(thinking.isStreaming)
        assertTrue(thinking.collapsed)
    }

    @Test
    fun rejectedNewBlockStartDoesNotAdmitAnUnrelatedDeltaAfterTheAnswer() {
        // Reproduced boundary 2: existing Thinking(0), Text(1), new Thinking(2).
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendReasoningDelta(runId, 1, 0, "原推理", emptyList())
        messages = projector.appendTextDelta(runId, 1, 1, "最终回答", messages)
        val answer = messages
        messages = startThinking(projector, messages, index = 2)
        assertEquals(answer, messages)
        messages = projector.appendReasoningDelta(runId, 1, 2, "完全不同的迟到推理", messages)
        assertEquals(answer, messages)
        messages = projector.finalizeThinkingBlock(runId, 1, 2, "迟到完整快照", messages)
        assertEquals(answer, messages)

        messages = projector.finalizeRun(runId, messages)
        assertEquals(listOf("$runId-thinking-1-0", "assistant-$runId-1-1"), messages.map { it.id })
        assertEquals("原推理", messages.filterIsInstance<ThinkingMessageUi>().single().content)
        val finalized = messages
        messages = hostedTool(projector, messages)
        messages = projector.appendReasoningDelta(runId, 1, 2, "仍迟到", messages)
        assertEquals(finalized, messages)
    }

    @Test
    fun sameIndexPrefixDeltaIsAppendedLiterallyAndBlockEndReplacesOnlyThatCard() {
        // Reproduced boundary 3: "A" + a real delta "AB" must be "AAB", not "A"/"AB".
        for ((initial, delta) in listOf("A" to "AB", "AB" to "A", "A" to "A")) {
            val projector = AgentRunMessageProjector { 1_000L }
            var messages = projector.appendReasoningDelta(runId, 1, 0, initial, emptyList())
            messages = projector.appendTextDelta(runId, 1, 1, "答案", messages)
            messages = startThinking(projector, messages, index = 0)
            messages = projector.appendReasoningDelta(runId, 1, 0, delta, messages)
            assertEquals(initial + delta, messages.filterIsInstance<ThinkingMessageUi>().single().content)
            assertEquals(listOf("$runId-thinking-1-0", "assistant-$runId-1-1"), messages.map { it.id })

            messages = projector.finalizeThinkingBlock(runId, 1, 0, "权威替换", messages)
            assertEquals("权威替换", messages.filterIsInstance<ThinkingMessageUi>().single().content)
            assertEquals("答案", messages.filterIsInstance<AgentMessageUi>().single().content)
            assertFalse(messages.filterIsInstance<ThinkingMessageUi>().single().isStreaming)
        }
    }

    @Test
    fun hostedToolFlushSurvivesAppStateRoundFinalization() {
        var now = 1_000L
        val projector = AgentRunMessageProjector { now }
        var messages = projector.appendTextDelta(runId, 1, 0, "准备搜索", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "拟定查询", messages)
        now = 5_000L
        // Match AgentAppState.HostedToolStarted: finalize both rounds BEFORE starting the tool.
        messages = projector.finalizeThinkingRound(runId, 1, messages)
        messages = projector.finalizeTextRound(runId, 1, messages)
        assertTrue(messages.none { it is ThinkingMessageUi })
        now = 9_000L
        messages = hostedTool(projector, messages)

        assertEquals(
            listOf("assistant-$runId-1-0", "$runId-thinking-1-1", "$runId-tool-1-web"),
            messages.map { it.id },
        )
        val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
        assertEquals("拟定查询", thinking.content)
        assertEquals(4, thinking.elapsedSeconds)
        assertFalse(thinking.isStreaming)
        assertTrue(thinking.collapsed)
    }

    @Test
    fun multiplePendingBlocksKeepFirstEventOrderAcrossInterleavedText() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 10, "说明一", emptyList())
        messages = startThinking(projector, messages, index = 7)
        messages = projector.appendTextDelta(runId, 1, 20, "说明二", messages)
        // The delta arrives after another text block, but must retain its BlockStart anchor.
        messages = projector.appendReasoningDelta(runId, 1, 7, "推理一", messages)
        messages = startThinking(projector, messages, index = 4)
        messages = projector.appendReasoningDelta(runId, 1, 4, "推理二", messages)
        messages = startThinking(projector, messages, index = 9)
        messages = projector.appendReasoningDelta(runId, 1, 9, "推理三", messages)
        messages = projector.appendTextDelta(runId, 1, 30, "说明三", messages)
        messages = projector.finalizeThinkingBlock(runId, 1, 7, "推理一定稿", messages)
        messages = projector.finalizeThinkingRound(runId, 1, messages)
        messages = projector.finalizeTextRound(runId, 1, messages)
        assertEquals(3, messages.size)
        messages = hostedTool(projector, messages)

        assertEquals(
            listOf(
                "assistant-$runId-1-10", "$runId-thinking-1-7", "assistant-$runId-1-20",
                "$runId-thinking-1-4", "$runId-thinking-1-9", "assistant-$runId-1-30", "$runId-tool-1-web",
            ),
            messages.map { it.id },
        )
        assertEquals(
            listOf("推理一定稿", "推理二", "推理三"),
            messages.filterIsInstance<ThinkingMessageUi>().map { it.content },
        )
        assertTrue(messages.filterIsInstance<ThinkingMessageUi>().all { !it.isStreaming && it.collapsed })
    }

    @Test
    fun pendingDeltasAppendAndBlockEndUsesFullReplacementIncludingEmptyString() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "说明", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "A", messages)
        messages = projector.appendReasoningDelta(runId, 1, 1, "AB", messages)
        messages = projector.finalizeThinkingBlock(runId, 1, 1, null, messages)
        messages = projector.appendReasoningDelta(runId, 1, 2, "过时", messages)
        messages = projector.finalizeThinkingBlock(runId, 1, 2, "全量替换", messages)
        messages = projector.appendReasoningDelta(runId, 1, 3, "应删除", messages)
        messages = projector.finalizeThinkingBlock(runId, 1, 3, "", messages)
        messages = startThinking(projector, messages, index = 4)
        messages = projector.finalizeThinkingBlock(runId, 1, 4, "只有结束快照", messages)
        assertEquals(1, messages.size)
        messages = hostedTool(projector, messages)

        assertEquals(
            listOf("AAB", "全量替换", "只有结束快照"),
            messages.filterIsInstance<ThinkingMessageUi>().map { it.content },
        )
        assertEquals(
            listOf("$runId-thinking-1-1", "$runId-thinking-1-2", "$runId-thinking-1-4"),
            messages.filterIsInstance<ThinkingMessageUi>().map { it.id },
        )
    }

    @Test
    fun pendingBlockCanResumeAfterEndWithoutChangingItsAnchor() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "说明一", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "A", messages)
        messages = projector.finalizeThinkingBlock(runId, 1, 1, "AB", messages)
        messages = projector.appendTextDelta(runId, 1, 2, "说明二", messages)
        messages = startThinking(projector, messages, index = 1)
        messages = projector.appendReasoningDelta(runId, 1, 1, "AB", messages)
        messages = hostedTool(projector, messages)

        assertEquals("ABAB", messages.filterIsInstance<ThinkingMessageUi>().single().content)
        assertEquals(
            listOf("assistant-$runId-1-0", "$runId-thinking-1-1", "assistant-$runId-1-2", "$runId-tool-1-web"),
            messages.map { it.id },
        )
    }

    @Test
    fun emptyAndInvisibleDeltasAndEmptyStartsNeverFlushEmptyCards() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "说明", emptyList())
        val before = messages
        messages = projector.appendReasoningDelta(runId, 1, 1, "", messages)
        messages = projector.appendReasoningDelta(runId, 1, 2, "隐藏推理", messages, visible = false)
        messages = startThinking(projector, messages, index = 3)
        messages = projector.finalizeThinkingBlock(runId, 1, 3, null, messages)
        messages = startThinking(projector, messages, index = 4)
        messages = projector.finalizeThinkingBlock(runId, 1, 4, "", messages)
        assertEquals(before, messages)
        messages = hostedTool(projector, messages)
        assertTrue(messages.none { it is ThinkingMessageUi })
    }

    @Test
    fun emptyDeltaDoesNotEraseAnExistingPendingBlock() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "说明", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "保留", messages)
        messages = projector.appendReasoningDelta(runId, 1, 1, "", messages)
        messages = hostedTool(projector, messages)
        assertEquals("保留", messages.filterIsInstance<ThinkingMessageUi>().single().content)
    }

    @Test
    fun oldOrDuplicateToolDoesNotUnlockReasoningAfterANewerTextBlock() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "先搜索", emptyList())
        messages = hostedTool(projector, messages, toolId = "first")
        messages = projector.appendTextDelta(runId, 1, 1, "再检查", messages)
        messages = projector.appendReasoningDelta(runId, 1, 2, "为第二个工具准备", messages)
        val pending = messages
        messages = hostedTool(projector, messages, toolId = "first")
        assertEquals(pending, messages)
        assertTrue(messages.none { it is ThinkingMessageUi })
        messages = hostedTool(projector, messages, toolId = "second")
        assertEquals(
            listOf(
                "assistant-$runId-1-0", "$runId-tool-1-first", "assistant-$runId-1-1",
                "$runId-thinking-1-2", "$runId-tool-1-second",
            ),
            messages.map { it.id },
        )
        val flushed = messages
        messages = hostedTool(projector, messages, toolId = "second")
        assertEquals(flushed, messages)
        messages = projector.appendTextDelta(runId, 1, 3, "最终回答", messages)
        messages = projector.appendReasoningDelta(runId, 1, 4, "最终回答后的新推理", messages)
        messages = projector.finalizeRun(runId, messages)
        assertEquals(listOf("为第二个工具准备"), messages.filterIsInstance<ThinkingMessageUi>().map { it.content })
    }

    @Test
    fun duplicateLocalToolDoesNotFlushPendingForALaterTool() {
        val projector = AgentRunMessageProjector { 1_000L }
        val first = AgentEvent.ToolStarted(1, "first", "search", "{}")
        var messages = projector.startTool(runId, first, emptyList())
        messages = projector.appendTextDelta(runId, 1, 0, "继续检查", messages)
        messages = projector.appendReasoningDelta(runId, 1, 1, "待确认", messages)
        val pending = messages
        messages = projector.startTool(runId, first, messages)
        assertEquals(pending, messages)
        messages = projector.startTool(runId, AgentEvent.ToolStarted(1, "second", "search", "{}"), messages)
        assertEquals(1, messages.filterIsInstance<ThinkingMessageUi>().size)
        assertEquals("$runId-thinking-1-1", messages[messages.lastIndex - 1].id)
    }

    @Test
    fun toolCallBlockStartAloneIsNotToolExecutionEvidence() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "说明", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "暂存", messages)
        messages = projector.startAssistantBlock(
            runId, AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 2), messages,
        )
        assertTrue(messages.none { it is ThinkingMessageUi })
        messages = projector.finalizeRun(runId, messages)
        assertTrue(messages.none { it is ThinkingMessageUi })
    }

    @Test
    fun toolFlushIsIsolatedByRunAndRound() {
        val projector = AgentRunMessageProjector { 1_000L }
        val otherRun = "pending-run-other"
        var messages = projector.appendTextDelta(runId, 1, 0, "第一轮", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "第一轮推理", messages)
        messages = projector.appendTextDelta(runId, 2, 0, "第二轮", messages)
        messages = projector.appendReasoningDelta(runId, 2, 1, "第二轮推理", messages)
        messages = projector.appendTextDelta(otherRun, 2, 0, "另一任务", messages)
        messages = projector.appendReasoningDelta(otherRun, 2, 1, "另一任务推理", messages)
        messages = hostedTool(projector, messages, round = 2)
        assertEquals(listOf("$runId-thinking-2-1"), messages.filterIsInstance<ThinkingMessageUi>().map { it.id })
        messages = projector.startHostedTool(otherRun, AgentEvent.HostedToolStarted(2, "other", "search"), messages)
        assertEquals(
            listOf("$runId-thinking-2-1", "$otherRun-thinking-2-1"),
            messages.filterIsInstance<ThinkingMessageUi>().map { it.id },
        )
        messages = hostedTool(projector, messages, round = 1)
        assertEquals(
            listOf("第一轮推理", "第二轮推理", "另一任务推理"),
            messages.filterIsInstance<ThinkingMessageUi>().map { it.content },
        )
    }

    @Test
    fun acceptedIndexInOneRoundDoesNotAdmitTheSameIndexInAnotherRound() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendReasoningDelta(runId, 1, 0, "第一轮推理", emptyList())
        messages = projector.appendTextDelta(runId, 2, 1, "第二轮回答", messages)
        messages = startThinking(projector, messages, index = 0, round = 2)
        messages = projector.appendReasoningDelta(runId, 2, 0, "第二轮迟到推理", messages)
        messages = projector.finalizeRun(runId, messages)
        assertEquals(listOf("第一轮推理"), messages.filterIsInstance<ThinkingMessageUi>().map { it.content })
    }

    @Test
    fun sealDiscardsPendingAndRejectsDeltaReplacementStartAndLateTool() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendReasoningDelta(runId, 1, 0, "原卡片", emptyList())
        messages = projector.appendTextDelta(runId, 1, 1, "答案", messages)
        messages = projector.appendReasoningDelta(runId, 1, 2, "不应出现", messages)
        projector.seal(runId)
        val sealed = messages
        messages = projector.appendReasoningDelta(runId, 1, 0, "AB", messages)
        messages = projector.finalizeThinkingBlock(runId, 1, 0, "迟到替换", messages)
        messages = projector.finalizeThinkingBlock(runId, 1, 2, "迟到快照", messages)
        messages = startThinking(projector, messages, index = 3)
        messages = hostedTool(projector, messages)
        messages = projector.startTool(runId, AgentEvent.ToolStarted(1, "local", "search", "{}"), messages)
        messages = projector.ensureCompletedThinking(runId, 1, "终态摘要", messages)
        assertEquals(sealed, messages)
        assertEquals("原卡片", messages.filterIsInstance<ThinkingMessageUi>().single().content)
    }

    @Test
    fun clearRunDropsPendingButDoesNotDropAnotherRunsPending() {
        val projector = AgentRunMessageProjector { 1_000L }
        val otherRun = "other-run"
        var messages = projector.appendTextDelta(runId, 1, 0, "已取消", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "应清除", messages)
        messages = projector.appendTextDelta(otherRun, 1, 0, "继续执行", messages)
        messages = projector.appendReasoningDelta(otherRun, 1, 1, "应保留", messages)
        projector.clearRun(runId)
        messages = hostedTool(projector, messages)
        assertTrue(messages.none { it is ThinkingMessageUi })
        messages = projector.startHostedTool(otherRun, AgentEvent.HostedToolStarted(1, "other", "search"), messages)
        assertEquals(listOf("应保留"), messages.filterIsInstance<ThinkingMessageUi>().map { it.content })
    }

    @Test
    fun sealKeepsOtherRunPendingAndClearRunDoesNotUnseal() {
        val projector = AgentRunMessageProjector { 1_000L }
        val otherRun = "other-run"
        var messages = projector.appendTextDelta(runId, 1, 0, "答案", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "清除", messages)
        messages = projector.appendTextDelta(otherRun, 1, 0, "说明", messages)
        messages = projector.appendReasoningDelta(otherRun, 1, 1, "保留", messages)
        projector.seal(runId)
        projector.clearRun(runId)
        assertTrue(projector.isSealed(runId))
        messages = projector.startHostedTool(otherRun, AgentEvent.HostedToolStarted(1, "other", "search"), messages)
        assertEquals(listOf("保留"), messages.filterIsInstance<ThinkingMessageUi>().map { it.content })
    }

    @Test
    fun stopRequestFinalizationKeepsPendingUntilRuntimeActuallySeals() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "工具前说明", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "仍合法", messages)
        messages = projector.finalizeThinking(runId, messages)
        assertFalse(projector.isSealed(runId))
        assertTrue(messages.none { it is ThinkingMessageUi })
        messages = hostedTool(projector, messages)
        assertEquals("仍合法", messages.filterIsInstance<ThinkingMessageUi>().single().content)
    }

    @Test
    fun replayResetClearsPendingAnchorsContentAndClockAndRemainsRepeatable() {
        var now = 1_000L
        val projector = AgentRunMessageProjector { now }
        val user = UserMessageUi("user-$runId", "搜索")
        var messages: List<AgentChatMessageUi> = listOf(user)
        messages = projector.appendTextDelta(runId, 1, 0, "旧说明", messages)
        messages = projector.appendReasoningDelta(runId, 1, 1, "旧暂存", messages)
        messages = projector.resetForReplay(runId, messages)
        assertEquals(listOf(user), messages)
        messages = hostedTool(projector, messages)
        assertTrue(messages.none { it is ThinkingMessageUi })

        repeat(3) {
            messages = projector.resetForReplay(runId, messages)
            now += 10_000L
            messages = projector.appendTextDelta(runId, 1, 0, "新说明", messages)
            messages = startThinking(projector, messages, index = 1)
            messages = projector.appendReasoningDelta(runId, 1, 1, "A", messages)
            messages = projector.appendReasoningDelta(runId, 1, 1, "AB", messages)
            now += 2_000L
            messages = hostedTool(projector, messages)
            messages = projector.finalizeRun(runId, messages)
            assertEquals(4, messages.size)
            val thinking = messages.filterIsInstance<ThinkingMessageUi>().single()
            assertEquals("AAB", thinking.content)
            assertEquals(2, thinking.elapsedSeconds)
        }
    }

    @Test
    fun retryDiscardsOnlyFailedRoundsPending() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "重试前", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "旧暂存", messages)
        messages = projector.scheduleModelRetry(runId, AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "MODEL_TIMEOUT"), messages)
        messages = hostedTool(projector, messages, round = 1)
        assertTrue(messages.none { it is ThinkingMessageUi })
        messages = projector.appendTextDelta(runId, 2, 0, "新说明", messages)
        messages = projector.appendReasoningDelta(runId, 2, 1, "新暂存", messages)
        messages = hostedTool(projector, messages, round = 2)
        assertEquals(listOf("新暂存"), messages.filterIsInstance<ThinkingMessageUi>().map { it.content })
    }

    @Test
    fun completedSummaryDoesNotDuplicateOrResurrectPendingButNonStreamingFallbackStillWorks() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "说明", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "流式推理", messages)
        messages = projector.ensureCompletedThinking(runId, 1, "流式推理", messages)
        assertTrue(messages.none { it is ThinkingMessageUi })
        messages = hostedTool(projector, messages)
        assertEquals(listOf("$runId-thinking-1-1"), messages.filterIsInstance<ThinkingMessageUi>().map { it.id })

        val noToolRun = "no-tool"
        var answer = projector.appendTextDelta(noToolRun, 1, 0, "最终回答", emptyList())
        answer = projector.appendReasoningDelta(noToolRun, 1, 1, "迟到推理", answer)
        answer = projector.ensureCompletedThinking(noToolRun, 1, "迟到推理", answer)
        answer = projector.finalizeRun(noToolRun, answer)
        assertTrue(answer.none { it is ThinkingMessageUi })

        val nonStreamingRun = "non-streaming"
        val text: List<AgentChatMessageUi> = listOf(AgentMessageUi("assistant-$nonStreamingRun-1-0", "回答"))
        val backfilled = projector.ensureCompletedThinking(nonStreamingRun, 1, "完整摘要", text)
        assertEquals(listOf("$nonStreamingRun-thinking-1-fallback", "assistant-$nonStreamingRun-1-0"), backfilled.map { it.id })
        assertEquals("完整摘要", backfilled.filterIsInstance<ThinkingMessageUi>().single().content)
        assertFalse(backfilled.filterIsInstance<ThinkingMessageUi>().single().isStreaming)
    }

    @Test
    fun existingCardFromRestoredUiAcceptsLiteralDeltaWithoutRememberedStart() {
        val projector = AgentRunMessageProjector { 1_000L }
        val initial: List<AgentChatMessageUi> = listOf(
            ThinkingMessageUi("$runId-thinking-1-0", "A", isStreaming = false),
            AgentMessageUi("assistant-$runId-1-1", "回答", isStreaming = false),
        )
        val messages = projector.appendReasoningDelta(runId, 1, 0, "AB", initial)
        assertEquals(initial.map { it.id }, messages.map { it.id })
        assertEquals("AAB", messages.filterIsInstance<ThinkingMessageUi>().single().content)
    }

    @Test
    fun acceptedEmptyBlockStartRetainsItsPositionWhenItsFirstDeltaArrivesAfterText() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi("user-$runId", "任务"))
        messages = startThinking(projector, messages, index = 0)
        messages = projector.appendTextDelta(runId, 1, 1, "回答", messages)
        messages = projector.appendReasoningDelta(runId, 1, 0, "原块补充", messages)
        assertEquals(
            listOf("user-$runId", "$runId-thinking-1-0", "assistant-$runId-1-1"),
            messages.map { it.id },
        )
        assertEquals("原块补充", messages.filterIsInstance<ThinkingMessageUi>().single().content)
    }

    @Test
    fun textWithNoContentDoesNotForceNormalReasoningIntoPending() {
        val projector = AgentRunMessageProjector { 1_000L }
        val initial: List<AgentChatMessageUi> = listOf(AgentMessageUi("assistant-$runId-1-0", "   "))
        val messages = projector.appendReasoningDelta(runId, 1, 1, "正常推理", initial)
        assertEquals("正常推理", messages.filterIsInstance<ThinkingMessageUi>().single().content)
    }

    @Test
    fun providerRequestBoundaryAllowsVisibleReasoningInAResumedSameRound() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "part1 ", emptyList())
        projector.beginProviderRequest(runId, 1)
        messages = projector.appendTextDelta(runId, 1, 0, "part2 ", messages)
        projector.beginProviderRequest(runId, 1)
        messages = projector.appendReasoningDelta(runId, 1, 1, "visible-later", messages)
        assertEquals(listOf("visible-later"), messages.filterIsInstance<ThinkingMessageUi>().map { it.content })
    }

    @Test
    fun sameRequestTextStillDefersReasoningAfterAProviderBoundary() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages: List<AgentChatMessageUi> = emptyList()
        projector.beginProviderRequest(runId, 1)
        messages = projector.appendTextDelta(runId, 1, 0, "final", messages)
        messages = projector.appendReasoningDelta(runId, 1, 1, "late", messages)
        assertTrue(messages.none { it is ThinkingMessageUi })
    }

    @Test
    fun oldToolDoesNotUnlockLateThinkingAfterSameTextIndexResumes() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "工具前说明", emptyList())
        messages = projector.startHostedTool(
            runId, AgentEvent.HostedToolStarted(1, "old-tool", "search"), messages,
        )
        messages = projector.appendTextDelta(runId, 1, 0, "最终回答", messages)
        val afterAnswer = messages

        messages = projector.appendReasoningDelta(runId, 1, 1, "迟到推理", messages)
        assertEquals(afterAnswer, messages)
        assertTrue(messages.none { it is ThinkingMessageUi })

        messages = projector.finalizeRun(runId, messages)
        assertEquals(afterAnswer.map { it.id }, messages.map { it.id })
        assertTrue(messages.none { it is ThinkingMessageUi })
        assertEquals("工具前说明最终回答", messages.filterIsInstance<AgentMessageUi>().single().content)
    }

    @Test
    fun flushedCardStillAcceptsLiteralDeltasAndReplacementsAtItsOriginalLocation() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta(runId, 1, 0, "说明", emptyList())
        messages = projector.appendReasoningDelta(runId, 1, 1, "A", messages)
        messages = hostedTool(projector, messages)
        messages = projector.appendTextDelta(runId, 1, 2, "回答", messages)
        val ids = messages.map { it.id }
        messages = projector.appendReasoningDelta(runId, 1, 1, "AB", messages)
        assertEquals("AAB", messages.filterIsInstance<ThinkingMessageUi>().single().content)
        messages = projector.finalizeThinkingBlock(runId, 1, 1, "最终快照", messages)
        assertEquals(ids, messages.map { it.id })
        assertEquals("最终快照", messages.filterIsInstance<ThinkingMessageUi>().single().content)
        assertEquals(1, messages.filterIsInstance<ToolActivityMessageUi>().size)
    }

    private fun startThinking(
        projector: AgentRunMessageProjector,
        messages: List<AgentChatMessageUi>,
        index: Int,
        round: Int = 1,
    ): List<AgentChatMessageUi> = projector.startAssistantBlock(
        runId, AgentEvent.AssistantBlockStart(round, AgentEvent.AssistantBlockKind.THINKING, index), messages,
    )

    private fun hostedTool(
        projector: AgentRunMessageProjector,
        messages: List<AgentChatMessageUi>,
        round: Int = 1,
        toolId: String = "web",
    ): List<AgentChatMessageUi> = projector.startHostedTool(
        runId, AgentEvent.HostedToolStarted(round, toolId, "search"), messages,
    )
}
