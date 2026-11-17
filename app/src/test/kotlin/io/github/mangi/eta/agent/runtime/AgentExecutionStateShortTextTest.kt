package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionStateShortTextTest {
    @Test
    fun testIdleHasNoCapsuleText() {
        assertEquals("", AgentExecutionState().shortText())
        assertEquals("", AgentExecutionState(phase = AgentExecutionPhase.IDLE, title = "🧠 深度思考…").shortText())
        assertFalse(AgentExecutionState().isRunning)
    }

    @Test
    fun testCapsulePrefersCurrentAction() {
        // 工具阶段：胶囊显示工具显示名（标题形如「⚡ 点击屏幕」，只留动作文字）。
        assertEquals(
            "点击屏幕",
            AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = "⚡ 点击屏幕").shortText(),
        )
        assertEquals(
            "执行终端命令",
            AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = "⚡ 执行终端命令").shortText(),
        )
        // 非工具阶段：用固定动作词，不受标题里的 emoji / 省略号影响。
        assertEquals(
            "思考中",
            AgentExecutionState(phase = AgentExecutionPhase.THINKING, title = "🧠 深度思考…").shortText(),
        )
        assertEquals(
            "生成中",
            AgentExecutionState(phase = AgentExecutionPhase.GENERATING, title = "✍️ 正在回复…").shortText(),
        )
        assertEquals(
            "准备中",
            AgentExecutionState(phase = AgentExecutionPhase.PREPARING, title = "🚀 正在准备…").shortText(),
        )
    }

    @Test
    fun testCapsuleFallsBackToPhaseLabel() {
        val expected = mapOf(
            AgentExecutionPhase.PREPARING to "准备中",
            AgentExecutionPhase.THINKING to "思考中",
            AgentExecutionPhase.TOOL_EXECUTING to "操作中",
            AgentExecutionPhase.GENERATING to "生成中",
            AgentExecutionPhase.TRANSLATING to "翻译中",
            AgentExecutionPhase.GENERIC_RUNNING to "运行中",
        )
        for ((phase, label) in expected) {
            assertEquals(label, AgentExecutionState(phase = phase).shortText())
            assertEquals(label, AgentExecutionState(phase = phase, title = "   ").shortText())
            assertEquals(label, AgentExecutionState.defaultCapsuleText(phase))
        }
    }

    @Test
    fun testStripCapsulePrefix() {
        assertEquals("纯文本标题", AgentExecutionState.stripCapsulePrefix("纯文本标题"))
        assertEquals("分析屏幕", AgentExecutionState.stripCapsulePrefix("⚡ 分析屏幕"))
        assertEquals("智能翻译", AgentExecutionState.stripCapsulePrefix("🌐 智能翻译…"))
        assertEquals("", AgentExecutionState.stripCapsulePrefix("⚡ ✨ "))
    }

    @Test
    fun testCapsuleLengthIsCapped() {
        val longTool = AgentExecutionState(
            phase = AgentExecutionPhase.TOOL_EXECUTING,
            title = "⚡ very_long_custom_tool_name_here",
        ).shortText()
        assertEquals(AgentExecutionState.SHORT_TEXT_MAX_CHARS, longTool.codePointCount(0, longTool.length))
        assertTrue(longTool.endsWith("…"))
        assertEquals("very_long_c…", longTool)

        val longLabel = AgentExecutionState.clampCapsuleText("一二三四五六七八九十甲乙丙丁", 12)
        assertEquals(12, longLabel.codePointCount(0, longLabel.length))
        assertEquals("一二三四五六七八九十甲…", longLabel)
    }

    @Test
    fun testCapsuleClampDoesNotSplitSurrogatePairs() {
        // 工具名里带 emoji（自定义工具很常见）：按码点截断，不能切出半个代理对。
        val title = "⚡ 分析" + "😀".repeat(20)
        val capsule = AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = title).shortText()
        assertEquals(12, capsule.codePointCount(0, capsule.length))
        assertTrue(capsule.endsWith("…"))
        assertFalse(hasUnpairedSurrogate(capsule))
        assertTrue(capsule.startsWith("分析"))
    }

    @Test
    fun testClampCapsuleTextEdgeCases() {
        assertEquals("", AgentExecutionState.clampCapsuleText(""))
        assertEquals("", AgentExecutionState.clampCapsuleText("   "))
        assertEquals("", AgentExecutionState.clampCapsuleText("标题", maxChars = 0))
        assertEquals("标题", AgentExecutionState.clampCapsuleText(" 标题 ", maxChars = 12))
    }

    @Test
    fun testEveryRunningPhaseStaysWithinLimit() {
        val longTitle = "⚡ " + "很长的动作名称".repeat(10)
        for (phase in AgentExecutionPhase.values()) {
            val capsule = AgentExecutionState(phase = phase, title = longTitle).shortText()
            if (phase == AgentExecutionPhase.IDLE) {
                assertEquals("", capsule)
            } else {
                assertTrue(capsule.isNotEmpty())
                assertTrue(capsule.codePointCount(0, capsule.length) <= AgentExecutionState.SHORT_TEXT_MAX_CHARS)
            }
        }
    }

    @Test
    fun testPromotionRequiresRunningStateOnAndroid16() {
        val running = AgentExecutionState(phase = AgentExecutionPhase.THINKING, title = "🧠 深度思考…")
        assertTrue(AgentExecutionState.shouldPromoteToLiveUpdate(running, 36))
        assertTrue(AgentExecutionState.shouldPromoteToLiveUpdate(running, 37))
        assertFalse(AgentExecutionState.shouldPromoteToLiveUpdate(AgentExecutionState(), 36))
    }

    @Test
    fun testLowVersionNeverPromotes() {
        // 低版本（Android 14/15）通知构建必须走原有分支：不论什么 phase 都不提升。
        for (sdk in intArrayOf(34, 35)) {
            for (phase in AgentExecutionPhase.values()) {
                val state = AgentExecutionState(phase = phase, title = "⚡ 点击屏幕")
                assertFalse(
                    "sdk=$sdk phase=$phase 不应提升为 Live Update",
                    AgentExecutionState.shouldPromoteToLiveUpdate(state, sdk),
                )
            }
        }
    }

    @Test
    fun testPromotionBoundaryMatchesAndroid16() {
        assertEquals(36, AgentExecutionState.LIVE_UPDATE_MIN_SDK)
        val running = AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = "⚡ 点击屏幕")
        assertFalse(AgentExecutionState.shouldPromoteToLiveUpdate(running, AgentExecutionState.LIVE_UPDATE_MIN_SDK - 1))
        assertTrue(AgentExecutionState.shouldPromoteToLiveUpdate(running, AgentExecutionState.LIVE_UPDATE_MIN_SDK))
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
