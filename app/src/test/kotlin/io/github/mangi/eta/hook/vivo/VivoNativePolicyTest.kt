package io.github.mangi.eta.hook.vivo

import org.junit.Assert.*
import org.junit.Test

class VivoNativePolicyTest {
    private val plain = VivoNativePolicy.Shape("little_v", 0, "", true, false, false, false, false, false)
    @Test fun scopeIsDefaultAgentManualTextOnly() {
        assertTrue(VivoNativePolicy.eligible(plain))
        listOf(plain.copy(agentId = "skill"), plain.copy(inputType = 1), plain.copy(bizSource = "BottomInput.VoiceClick"),
            plain.copy(renderText = false), plain.copy(shortcut = true), plain.copy(regenerate = true),
            plain.copy(skipRemote = true), plain.copy(recommended = true), plain.copy(specialized = true)).forEach {
            assertFalse(VivoNativePolicy.eligible(it))
        }
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
