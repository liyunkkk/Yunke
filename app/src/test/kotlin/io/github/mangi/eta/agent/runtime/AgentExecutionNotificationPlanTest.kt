package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionNotificationPlanTest {
    private val expectedMiuiKeys = listOf(
        "miui.focus.param",
        "miui.focus.pics",
    )

    @Test
    fun testMiuiFocusKeysMatchMinimalDshGoFallback() {
        assertEquals(expectedMiuiKeys, ExecutionNotificationPlan.MIUI_FOCUS_KEYS)
        assertEquals(2, ExecutionNotificationPlan.MIUI_FOCUS_KEYS.size)
    }

    @Test
    fun testPromotedStateWritesNoMiuiFocusExtras() {
        val keys = ExecutionNotificationPlan.miuiFocusKeys(promoted = true)
        assertTrue(keys.isEmpty())
        assertFalse(keys.any { it.startsWith("miui.") })
    }

    @Test
    fun testNonPromotedStateKeepsMinimalXiaomiFocusKeys() {
        val keys = ExecutionNotificationPlan.miuiFocusKeys(promoted = false)
        assertEquals(expectedMiuiKeys, keys)
        assertTrue(keys.all { it.startsWith("miui.") })
    }

    @Test
    fun testPromotedStateUsesIndeterminateProgressStyle() {
        assertEquals(ExecutionNotificationStyle.PROGRESS, ExecutionNotificationPlan.style(promoted = true, hasExpandedSnippet = false))
        assertEquals(ExecutionNotificationStyle.PROGRESS, ExecutionNotificationPlan.style(promoted = true, hasExpandedSnippet = true))
    }

    @Test
    fun testNonPromotedStyleMatchesPreviousBehavior() {
        assertEquals(ExecutionNotificationStyle.BIG_TEXT, ExecutionNotificationPlan.style(promoted = false, hasExpandedSnippet = true))
        assertEquals(ExecutionNotificationStyle.NONE, ExecutionNotificationPlan.style(promoted = false, hasExpandedSnippet = false))
    }

    @Test
    fun testAndroid16RunningStateDropsMiuiExtrasAndPromotes() {
        val running = AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = "⚡ 点击屏幕")
        val promote = AgentExecutionState.shouldPromoteToLiveUpdate(running, 36)
        assertTrue(promote)
        assertEquals(emptyList<String>(), ExecutionNotificationPlan.miuiFocusKeys(promote))
        assertEquals(ExecutionNotificationStyle.PROGRESS, ExecutionNotificationPlan.style(promote, hasExpandedSnippet = false))
    }

    @Test
    fun testLowVersionAndIdleKeepMinimalMiuiFallback() {
        val running = AgentExecutionState(phase = AgentExecutionPhase.THINKING, title = "🧠 深度思考…")
        for (sdk in intArrayOf(34, 35)) {
            val promote = AgentExecutionState.shouldPromoteToLiveUpdate(running, sdk)
            assertFalse(promote)
            assertEquals(expectedMiuiKeys, ExecutionNotificationPlan.miuiFocusKeys(promote))
        }
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
