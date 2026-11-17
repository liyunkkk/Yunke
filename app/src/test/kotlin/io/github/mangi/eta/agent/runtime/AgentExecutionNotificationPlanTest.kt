package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionNotificationPlanTest {
    private val expectedMiuiKeys = listOf(
        "miui.focusNotification",
        "miui.focusNotification.subTitle",
        "miui.focus.isFocus",
        "miui.focus.ticker",
        "miui.enableFloat",
        "miui.focus.param",
        "miui.focus.pics",
        "miui.focus.actions",
    )

    @Test
    fun testMiuiFocusKeysCoverEveryLegacyExtra() {
        assertEquals(expectedMiuiKeys, ExecutionNotificationPlan.MIUI_FOCUS_KEYS)
        assertEquals(8, ExecutionNotificationPlan.MIUI_FOCUS_KEYS.size)
    }

    @Test
    fun testPromotedStateWritesNoMiuiFocusExtras() {
        val keys = ExecutionNotificationPlan.miuiFocusKeys(promoted = true)
        assertTrue(keys.isEmpty())
        assertFalse(keys.any { it.startsWith("miui.") })
        assertFalse(keys.contains("miui.focusNotification"))
        assertFalse(keys.contains("miui.focus.isFocus"))
        assertFalse(keys.contains("miui.focus.ticker"))
        assertFalse(keys.contains("miui.focus.param"))
        assertFalse(keys.contains("miui.focus.pics"))
        assertFalse(keys.contains("miui.focus.actions"))
        assertFalse(keys.contains("miui.enableFloat"))
    }

    @Test
    fun testNonPromotedStateKeepsEveryMiuiFocusExtra() {
        val keys = ExecutionNotificationPlan.miuiFocusKeys(promoted = false)
        assertEquals(expectedMiuiKeys, keys)
        assertTrue(keys.all { it.startsWith("miui.") })
    }

    @Test
    fun testPromotedStateUsesBigTextStyle() {
        assertEquals(ExecutionNotificationStyle.BIG_TEXT, ExecutionNotificationPlan.style(promoted = true, hasExpandedSnippet = false))
        assertEquals(ExecutionNotificationStyle.BIG_TEXT, ExecutionNotificationPlan.style(promoted = true, hasExpandedSnippet = true))
    }

    @Test
    fun testNonPromotedStyleMatchesPreviousBehavior() {
        // 改动前：有展开正文才设 BigTextStyle，没有就完全不设样式。
        assertEquals(ExecutionNotificationStyle.BIG_TEXT, ExecutionNotificationPlan.style(promoted = false, hasExpandedSnippet = true))
        assertEquals(ExecutionNotificationStyle.NONE, ExecutionNotificationPlan.style(promoted = false, hasExpandedSnippet = false))
    }

    @Test
    fun testAndroid16RunningStateDropsMiuiExtrasAndPromotes() {
        val running = AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = "⚡ 点击屏幕")
        val promote = AgentExecutionState.shouldPromoteToLiveUpdate(running, 36)
        assertTrue(promote)
        // 提升态：零 miui.*，BigTextStyle。
        assertEquals(emptyList<String>(), ExecutionNotificationPlan.miuiFocusKeys(promote))
        assertEquals(ExecutionNotificationStyle.BIG_TEXT, ExecutionNotificationPlan.style(promote, hasExpandedSnippet = false))
    }

    @Test
    fun testLowVersionAndIdleKeepMiuiExtras() {
        val running = AgentExecutionState(phase = AgentExecutionPhase.THINKING, title = "🧠 深度思考…")
        for (sdk in intArrayOf(34, 35)) {
            val promote = AgentExecutionState.shouldPromoteToLiveUpdate(running, sdk)
            assertFalse(promote)
            assertEquals(expectedMiuiKeys, ExecutionNotificationPlan.miuiFocusKeys(promote))
        }
        // Android 16 但没有 Agent run 在跑（只剩终端/PRoot 会话）：同样保持 MIUI 岛行为。
        val idlePromote = AgentExecutionState.shouldPromoteToLiveUpdate(AgentExecutionState(), 36)
        assertFalse(idlePromote)
        assertEquals(expectedMiuiKeys, ExecutionNotificationPlan.miuiFocusKeys(idlePromote))
    }
}
