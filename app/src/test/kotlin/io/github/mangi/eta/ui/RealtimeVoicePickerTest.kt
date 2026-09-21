package io.github.mangi.eta.ui

import io.github.mangi.eta.agent.voice.DoubaoRealtimeVoices
import io.github.mangi.eta.agent.voice.tts.SpeechVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeVoicePickerTest {
    @Test fun realtimeCatalogUsesTheSameGenderAndPersonalSectionsAsReadAloud() {
        val personal = SpeechVoice("etaClone-1", "少女", personal = true)
        val sections = groupedSpeechVoices(DoubaoRealtimeVoices.catalog + personal)
        assertEquals(listOf("Vivi", "小何"), sections.female.map { it.name })
        assertEquals(listOf("云舟", "小天"), sections.male.map { it.name })
        assertTrue(sections.other.isEmpty())
        assertEquals(listOf("少女"), sections.personal.map { it.name })
    }
}
