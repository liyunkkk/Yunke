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

    @Test
    fun testCompletionBodyKeepsShortReply() {
        val short = "已把截图存到相册，另外帮你整理了三条待办。"
        assertEquals(short, ExecutionNotificationPlan.completionBody(short))
        assertEquals("", ExecutionNotificationPlan.completionBody(""))
        assertEquals("", ExecutionNotificationPlan.completionBody("   "))
        assertEquals("去掉首尾空白", ExecutionNotificationPlan.completionBody("\n 去掉首尾空白 \n"))
    }

    @Test
    fun testCompletionBodyIsBoundedButLongerThanBefore() {
        // 提交流程过去把正文砍到 200 字；现在上限拉到 800，展开卡能看完整一段回复。
        assertEquals(800, ExecutionNotificationPlan.MAX_COMPLETION_BODY_CHARS)
        val medium = "一".repeat(400)
        assertEquals(medium, ExecutionNotificationPlan.completionBody(medium))

        val huge = "回".repeat(5000)
        val body = ExecutionNotificationPlan.completionBody(huge)
        assertEquals(ExecutionNotificationPlan.MAX_COMPLETION_BODY_CHARS, body.codePointCount(0, body.length))
        assertTrue(body.endsWith("…"))
    }

    @Test
    fun testCompletionBodyDoesNotSplitSurrogatePairs() {
        val text = "总结" + "😀".repeat(2000)
        val body = ExecutionNotificationPlan.completionBody(text)
        assertEquals(ExecutionNotificationPlan.MAX_COMPLETION_BODY_CHARS, body.codePointCount(0, body.length))
        assertTrue(body.startsWith("总结"))
        assertTrue(body.endsWith("…"))
        assertFalse(hasUnpairedSurrogate(body))
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                Character.isHighSurrogate(char) -> {
                    if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                    index += 2
                }
                Character.isLowSurrogate(char) -> return true
                else -> index++
            }
        }
        return false
    }
}
