// staging: TurnTickRailTest.kt
// 目标路径（新增）: app/src/test/kotlin/io/github/mangi/eta/ui/components/TurnTickRailTest.kt
// 只测纯函数 resolveVisibleTurn（不依赖 Android 运行时）；buildTurnTicks 需构造
// UserMessageUi / AgentMessageUi 真实例，构造参数未在本次核对中确认，
// 留待集成时补（见 README 待核对）。

package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
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
        // 边界 2 与 6 之间：位置 4 正好是第 1 轮的中点。
        assertEquals(1.5f, resolveVisibleTurn(4f, listOf(2, 6, 10), 14), 0.0001f)
        assertEquals(1.25f, resolveVisibleTurn(3f, listOf(2, 6, 10), 14), 0.0001f)
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
}
