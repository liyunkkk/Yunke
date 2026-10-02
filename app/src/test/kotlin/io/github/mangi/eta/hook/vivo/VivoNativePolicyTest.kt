package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.vivo.VivoBridgeDiagnostics.Reason
import org.junit.Assert.*
import org.junit.Test

class VivoNativePolicyTest {
    private val plain = VivoNativePolicy.Shape("little_v", 0, "", true, false, false, false, false, false)
    @Test fun everyAllowedSourceStillRequiresEveryOtherGate() {
        listOf(null, "", "BottomInput").forEach { source ->
            val manual = plain.copy(bizSource = source)
            assertTrue(VivoNativePolicy.eligible(manual))
            assertNull(VivoNativePolicy.rejection(manual))
            listOf(manual.copy(agentId = "skill") to Reason.AGENT_ID,
                manual.copy(agentId = null) to Reason.AGENT_ID,
                manual.copy(inputType = 1) to Reason.INPUT_TYPE,
                manual.copy(renderText = false) to Reason.RENDER_TEXT,
                manual.copy(shortcut = true) to Reason.SHORTCUT,
                manual.copy(regenerate = true) to Reason.REGENERATE,
                manual.copy(skipRemote = true) to Reason.SKIP_REMOTE,
                manual.copy(recommended = true) to Reason.RECOMMENDED,
                manual.copy(specialized = true) to Reason.SPECIALIZED).forEach { (shape, reason) ->
                assertFalse(VivoNativePolicy.eligible(shape))
                assertEquals(reason, VivoNativePolicy.rejection(shape))
            }
        }
    }
    @Test fun sourceComparisonIsExactAndDoesNotNormalizeOrAcceptVoice() {
        listOf("voice_click", "BottomInput.VoiceClick", "BottomInput.LongPress", "BottomInput.VoiceLongPress",
            "bottominput", "BOTTOMINPUT", "bottomInput", " BottomInput", "BottomInput ", "BottomInput\n",
            "BottomInput\u0000", "BottomInput.text", "BottomInputSuffix", " ", "\t").forEach {
            assertEquals(Reason.BIZ_SOURCE, VivoNativePolicy.rejection(plain.copy(bizSource = it)))
            assertFalse(VivoNativePolicy.eligible(plain.copy(bizSource = it)))
        }
    }
    @Test fun firstPolicyRejectionHasStableOrder() {
        assertEquals(Reason.AGENT_ID, VivoNativePolicy.rejection(plain.copy(agentId = "other", inputType = 1)))
        assertEquals(Reason.INPUT_TYPE, VivoNativePolicy.rejection(plain.copy(inputType = 1, bizSource = "voice_click")))
        assertEquals(Reason.BIZ_SOURCE, VivoNativePolicy.rejection(plain.copy(bizSource = "voice_click", specialized = true)))
    }
    @Test fun prefixIsHonoredWithoutChangingExistingPreference() {
        assertEquals("hello", VivoNativePolicy.prompt(" Agent: hello ", true))
        assertEquals("你好", VivoNativePolicy.prompt("agent：你好", true))
        assertEquals("hello", VivoNativePolicy.prompt("Agent hello", true))
        assertNull(VivoNativePolicy.prompt("Agenthello", true))
        assertNull(VivoNativePolicy.prompt("hello", true))
        assertEquals("Agent: hello", VivoNativePolicy.prompt("Agent: hello", false))
    }
    @Test fun slashPrefixedAgentSamplesAreAccepted() {
        assertEquals("你好", VivoNativePolicy.prompt("/agent 你好", true))
        assertEquals("你是谁", VivoNativePolicy.prompt("/agent 你是谁", true))
        assertEquals("hello", VivoNativePolicy.prompt("/Agent: hello", true))
        assertEquals("你好", VivoNativePolicy.prompt("/agent：你好", true))
    }
    @Test fun slashPrefixStillNeedsAgentWordAndDelimiter() {
        assertNull(VivoNativePolicy.prompt("/agent你好", true))
        assertNull(VivoNativePolicy.prompt("/agent", true))
        assertNull(VivoNativePolicy.prompt("/agent ", true))
        assertNull(VivoNativePolicy.prompt("/agent：", true))
        assertNull(VivoNativePolicy.prompt("/agent:", true))
        assertNull(VivoNativePolicy.prompt("/hello", true))
        assertNull(VivoNativePolicy.prompt("/agentX hello", true))
    }
    @Test fun prefixDisabledKeepsSlashTextVerbatim() {
        assertEquals("/agent 你好", VivoNativePolicy.prompt("/agent 你好", false))
        assertEquals("/agent 你是谁", VivoNativePolicy.prompt("/agent 你是谁", false))
    }
    @Test fun promptAndNativeIdsAreBounded() {
        assertNull(VivoNativePolicy.prompt("a\u0000b", false))
        assertNull(VivoNativePolicy.prompt("/agent a\u0000b", true))
        assertNull(VivoNativePolicy.prompt("a".repeat(4001), false))
        assertNull(VivoNativePolicy.prompt("/agent " + "a".repeat(4001), true))
        assertNull(VivoNativePolicy.prompt("Agent:", true))
        assertTrue(VivoNativePolicy.validId("abc-123_4:5.6"))
        assertFalse(VivoNativePolicy.validId("a\"b"))
        assertFalse(VivoNativePolicy.validId("x".repeat(161)))
    }
}
