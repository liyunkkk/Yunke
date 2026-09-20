package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import org.junit.Assert.*
import org.junit.Test

class VoiceEntryPolicyTest {
    @Test fun allEightCombinationsExposeOnlyEnabledModes() {
        for (bits in 0..7) {
            val config = DoubaoVoiceConfig.Config(
                inputEnabled = bits and 1 != 0,
                conversationEnabled = bits and 2 != 0,
                duplexEnabled = bits and 4 != 0,
            )
            val expected = buildList {
                if (bits and 1 != 0) add(VoiceEntryMode.DICTATION)
                if (bits and 2 != 0) add(VoiceEntryMode.UNIVERSAL)
                if (bits and 4 != 0) add(VoiceEntryMode.DOUBAO_DUPLEX)
            }
            assertEquals(expected, VoiceEntryPolicy.modes(config))
            assertEquals(expected.singleOrNull(), VoiceEntryPolicy.directMode(config))
            assertEquals(expected.size > 1, VoiceEntryPolicy.canChoose(config))
            VoiceEntryMode.entries.forEach {
                assertEquals(it in expected, VoiceEntryPolicy.enabled(config, it))
            }
        }
    }

    @Test fun allCombinationsRespectLastChoiceWithoutEnablingDisabledModes() {
        for (bits in 0..7) {
            val config = DoubaoVoiceConfig.Config(
                inputEnabled = bits and 1 != 0,
                conversationEnabled = bits and 2 != 0,
                duplexEnabled = bits and 4 != 0,
            )
            val available = VoiceEntryPolicy.modes(config)
            VoiceEntryMode.entries.forEach { last ->
                val expected = last.takeIf { it in available } ?: available.singleOrNull()
                assertEquals(expected, VoiceEntryPolicy.directMode(config, last.wireValue))
            }
            assertEquals(available.singleOrNull(), VoiceEntryPolicy.directMode(config, "unknown-mode"))
        }
    }

    @Test fun multipleEnabledModesUseTheRememberedConversationOnNextClick() {
        val config = DoubaoVoiceConfig.Config(inputEnabled = true, conversationEnabled = true, duplexEnabled = true)
        assertEquals(VoiceEntryMode.UNIVERSAL, VoiceEntryPolicy.directMode(config, "universal"))
        assertEquals(VoiceEntryMode.DOUBAO_DUPLEX, VoiceEntryPolicy.directMode(config, "doubao_duplex"))
        assertEquals(VoiceEntryMode.DICTATION, VoiceEntryPolicy.directMode(config, "dictation"))
        assertNull(VoiceEntryPolicy.directMode(config, ""))
        assertTrue(VoiceEntryPolicy.canChoose(config))
    }

    @Test fun disabledHistoryOpensChooserUnlessOnlyOneModeRemains() {
        val two = DoubaoVoiceConfig.Config(inputEnabled = true, conversationEnabled = true, duplexEnabled = false)
        assertNull(VoiceEntryPolicy.directMode(two, "doubao_duplex"))
        assertEquals(VoiceEntryMode.UNIVERSAL, VoiceEntryPolicy.directMode(two.copy(inputEnabled = false), "doubao_duplex"))
        assertNull(VoiceEntryPolicy.directMode(two.copy(inputEnabled = false, conversationEnabled = false), "doubao_duplex"))
    }
}
