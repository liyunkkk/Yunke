package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KimiDelegationTimeoutTest {

    @Test
    fun missingOrNonPositiveTimeoutFallsBackToDefault() {
        // 不再出现「0s 预算」：缺失、0、负数都回落默认值。
        assertEquals(180, KimiCodeSubagentTool.resolveTimeoutSeconds(null))
        assertEquals(180, KimiCodeSubagentTool.resolveTimeoutSeconds(0))
        assertEquals(180, KimiCodeSubagentTool.resolveTimeoutSeconds(-5))
    }

    @Test
    fun positiveTimeoutIsClampedToSupportedRange() {
        assertEquals(10, KimiCodeSubagentTool.resolveTimeoutSeconds(1))
        assertEquals(120, KimiCodeSubagentTool.resolveTimeoutSeconds(120))
        assertEquals(600, KimiCodeSubagentTool.resolveTimeoutSeconds(99_999))
    }

    @Test
    fun abortedMessageStatesUnknownAndKeepsBusyWhenKnown() {
        val unknown = KimiCodeSubagentTool.abortedMessage(null)
        assertTrue(unknown.contains("任务状态未知"))
        assertTrue(unknown.contains("请核实"))

        // 能读到 busy 时，在跑的会话必须明确标出「仍在执行」。
        assertTrue(KimiCodeSubagentTool.abortedMessage(true).contains("Kimi 会话仍在执行"))
        assertTrue(KimiCodeSubagentTool.abortedMessage(false).contains("未在执行"))
    }
}
