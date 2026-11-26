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
    fun testCapsulePrefersCurrentActionWithFixedSixCharWidth() {
        // 工具阶段：6 字工具名保持原样；4 字工具名自动补「正在」成自然 6 字。
        assertEquals(
            "正在点击屏幕",
            AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = "⚡ 点击屏幕").shortText(),
        )
        assertEquals(
            "执行终端命令",
            AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = "⚡ 执行终端命令").shortText(),
        )
        // 非工具阶段：统一为自然 6 字动作词，胶囊长度与「执行终端命令」完全一致。
        assertEquals(
            "正在深度思考",
            AgentExecutionState(phase = AgentExecutionPhase.THINKING, title = "🧠 深度思考…").shortText(),
        )
        assertEquals(
            "正在生成回复",
            AgentExecutionState(phase = AgentExecutionPhase.GENERATING, title = "✍️ 正在回复…").shortText(),
        )
        assertEquals(
            "准备执行任务",
            AgentExecutionState(phase = AgentExecutionPhase.PREPARING, title = "🚀 正在准备…").shortText(),
        )
    }

    @Test
    fun testCapsuleFallsBackToPhaseLabel() {
        val expected = mapOf(
            AgentExecutionPhase.PREPARING to "准备执行任务",
            AgentExecutionPhase.THINKING to "正在深度思考",
            AgentExecutionPhase.TOOL_EXECUTING to "正在执行操作",
            AgentExecutionPhase.GENERATING to "正在生成回复",
            AgentExecutionPhase.TRANSLATING to "正在翻译屏幕",
            AgentExecutionPhase.GENERIC_RUNNING to "任务正在运行",
        )
        for ((phase, label) in expected) {
            assertEquals(label, AgentExecutionState(phase = phase).shortText())
            assertEquals(label, AgentExecutionState(phase = phase, title = "   ").shortText())
            assertEquals(label, AgentExecutionState.defaultCapsuleText(phase))
            assertEquals(12, AgentExecutionState.visualUnits(label))
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
    fun testCapsuleLengthIsFixedToSixFullWidthChars() {
        val longTool = AgentExecutionState(
            phase = AgentExecutionPhase.TOOL_EXECUTING,
            title = "⚡ very_long_custom_tool_name_here",
        ).shortText()
        assertEquals(12, AgentExecutionState.visualUnits(longTool))
        assertTrue(longTool.endsWith("…"))
        assertEquals("very_long_…", longTool)

        val fiveCharTool = AgentExecutionState(
            phase = AgentExecutionPhase.TOOL_EXECUTING,
            title = "⚡ 搜索联系人",
        ).shortText()
        assertEquals(12, AgentExecutionState.visualUnits(fiveCharTool))
        assertTrue(fiveCharTool.endsWith("搜索联系人"))

        val longLabel = AgentExecutionState.clampCapsuleText("一二三四五六七八九十甲乙丙丁", 12)
        assertEquals(12, longLabel.codePointCount(0, longLabel.length))
        assertEquals("一二三四五六七八九十甲…", longLabel)
    }

    @Test
    fun testCapsuleClampDoesNotSplitSurrogatePairs() {
        // 工具名里带 emoji（自定义工具很常见）：按码点截断，不能切出半个代理对。
        val title = "⚡ 分析" + "😀".repeat(20)
        val capsule = AgentExecutionState(phase = AgentExecutionPhase.TOOL_EXECUTING, title = title).shortText()
        assertEquals(12, AgentExecutionState.visualUnits(capsule))
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
    fun testEveryRunningPhaseStaysUniformWidth() {
        val longTitle = "⚡ " + "很长的动作名称".repeat(10)
        for (phase in AgentExecutionPhase.values()) {
            val capsule = AgentExecutionState(phase = phase, title = longTitle).shortText()
            if (phase == AgentExecutionPhase.IDLE) {
                assertEquals("", capsule)
            } else {
                assertTrue(capsule.isNotEmpty())
                assertEquals(12, AgentExecutionState.visualUnits(capsule))
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

    @Test
    fun testSuppressLiveUpdateBlocksPromotion() {
        val suppressed = AgentExecutionState(
            phase = AgentExecutionPhase.TOOL_EXECUTING,
            title = "⚡ 点击屏幕",
            suppressLiveUpdate = true,
        )
        assertFalse(AgentExecutionState.shouldPromoteToLiveUpdate(suppressed, 36))
    }

    @Test
    fun testPreferMiuiFocusBlocksLiveUpdatePromotion() {
        val running = AgentExecutionState(
            phase = AgentExecutionPhase.THINKING,
            title = "🧠 深度思考…",
        )
        assertTrue(AgentExecutionState.shouldPromoteToLiveUpdate(running, 36, preferMiuiFocus = false))
        assertFalse(AgentExecutionState.shouldPromoteToLiveUpdate(running, 36, preferMiuiFocus = true))
    }

    @Test
    fun testIslandShortTextStaysFixedSixChars() {
        val longTool = AgentExecutionState(
            phase = AgentExecutionPhase.TOOL_EXECUTING,
            title = "⚡ 正在梳理推导步骤并且准备工具",
        )
        assertEquals(6, longTool.islandShortText().codePointCount(0, longTool.islandShortText().length))
        assertEquals(12, AgentExecutionState.visualUnits(longTool.islandShortText()))
        assertTrue(longTool.islandShortText().endsWith("…"))
        assertEquals("正在深度思考", AgentExecutionState(phase = AgentExecutionPhase.THINKING, title = "正在梳理推导步骤").islandShortText())
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
