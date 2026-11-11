package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTickRailTest {

    @Test
    fun `resolveVisibleTurn returns -1 when no boundaries`() {
        assertEquals(-1f, resolveVisibleTurn(0f, emptyList(), 10), 0.0001f)
    }

    @Test
    fun `resolveVisibleTurn clamps before first boundary to 0`() {
        assertEquals(0f, resolveVisibleTurn(0f, listOf(2, 6, 10), 14), 0.0001f)
        assertEquals(0f, resolveVisibleTurn(1.5f, listOf(2, 6, 10), 14), 0.0001f)
    }

    @Test
    fun `resolveVisibleTurn interpolates between boundaries`() {
        // 边界 2 与 6 之间是第 0 轮到第 1 轮：位置 4 正好是中点 0.5（0-based 插值，
        // 与实现 i + (pos-start)/(end-start) 一致；旧期望 1.5/1.25 是按 1-based
        // 误写的，CI 2026-11-11 run 260 因此失败，已修正）。
        assertEquals(0.5f, resolveVisibleTurn(4f, listOf(2, 6, 10), 14), 0.0001f)
        assertEquals(0.25f, resolveVisibleTurn(3f, listOf(2, 6, 10), 14), 0.0001f)
    }

    @Test
    fun `resolveVisibleTurn clamps at last turn`() {
        // 末轮跨度 totalItems - lastBoundary = 14 - 10 = 4；位置 14 应为末轮 2.0。
        assertEquals(2f, resolveVisibleTurn(14f, listOf(2, 6, 10), 14), 0.0001f)
        assertEquals(2f, resolveVisibleTurn(99f, listOf(2, 6, 10), 14), 0.0001f)
    }

    @Test
    fun `resolveVisibleTurn handles single boundary`() {
        assertEquals(0f, resolveVisibleTurn(5f, listOf(5), 9), 0.0001f)
    }

    // ── buildTurnTicks ────────────────────────────────────────────────────
    // 构造参数已核对：UserMessageUi(id, content, ...)、AgentMessageUi(id, content, ...)
    // 其余字段都有默认值；AgentTimelineEntry.Message(message) 只需要一个 AgentChatMessageUi。

    @Test
    fun `buildTurnTicks closes a turn on the next user message`() {
        val ticks = buildTurnTicks(
            listOf(
                userEntry(0, "u1", "第一问"),
                agentEntry(1, "a1", "第一答"),
                userEntry(2, "u2", "第二问"),
                agentEntry(3, "a2", "第二答"),
            ),
        )
        assertEquals(2, ticks.size)
        // 刻度记录的是该轮首条用户消息的 ID，跳转靠它换算行下标。
        assertEquals("u1", ticks[0].firstUserMessageId)
        assertEquals(0, ticks[0].timelineIndex)
        assertEquals("u2", ticks[1].firstUserMessageId)
        assertEquals(2, ticks[1].timelineIndex)
        // 输入取用户原文，输出取本轮最后一条助手消息。
        assertEquals("第一问", ticks[0].inputSummary)
        assertEquals("第一答", ticks[0].outputSummary)
    }

    @Test
    fun `buildTurnTicks keeps the last assistant message as output`() {
        val ticks = buildTurnTicks(
            listOf(
                userEntry(0, "u1", "问"),
                agentEntry(1, "a1", "答一"),
                agentEntry(2, "a2", "答二"),
            ),
        )
        assertEquals(1, ticks.size)
        assertEquals("答二", ticks[0].outputSummary)
    }

    @Test
    fun `buildTurnTicks ignores an assistant message that has no user before it`() {
        val ticks = buildTurnTicks(
            listOf(
                agentEntry(0, "a0", "开场白"),
                userEntry(1, "u1", "问"),
                agentEntry(2, "a1", "答"),
            ),
        )
        assertEquals(1, ticks.size)
        assertEquals("u1", ticks[0].firstUserMessageId)
    }

    @Test
    fun `buildTurnTicks collapses whitespace and truncates long input`() {
        val long = "  多行\n\n文本 " + "x".repeat(200)
        val ticks = buildTurnTicks(
            listOf(
                userEntry(0, "u1", long),
                agentEntry(1, "a1", "答"),
            ),
        )
        assertEquals(1, ticks.size)
        // 折叠空白后超过 160 字符会截断并加省略号。
        assertTrue(ticks[0].inputSummary.endsWith("…"))
        assertTrue(ticks[0].inputSummary.length <= 161)
        assertFalse(ticks[0].inputSummary.contains("\n"))
    }

    @Test
    fun `buildTurnTicks returns empty list for no entries`() {
        assertTrue(buildTurnTicks(emptyList()).isEmpty())
    }

    // 时间线条目下标由列表顺序决定，不需要显式传入，避免未使用参数告警。
    private fun userEntry(@Suppress("UNUSED_PARAMETER") index: Int, id: String, text: String) =
        AgentTimelineEntry.Message(UserMessageUi(id = id, content = text))

    private fun agentEntry(@Suppress("UNUSED_PARAMETER") index: Int, id: String, text: String) =
        AgentTimelineEntry.Message(AgentMessageUi(id = id, content = text))
}
