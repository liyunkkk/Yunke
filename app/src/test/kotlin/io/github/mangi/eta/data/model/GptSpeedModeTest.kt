package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GptSpeedModeTest {
    @Test fun cyclesThroughAllThreeModes() {
        assertEquals(GptSpeedMode.FAST, GptSpeedMode.NORMAL.next())
        assertEquals(GptSpeedMode.ULTRA_FAST, GptSpeedMode.FAST.next())
        assertEquals(GptSpeedMode.NORMAL, GptSpeedMode.ULTRA_FAST.next())
    }

    @Test fun acceptsNumberedGptIdsWithoutAVersionAllowlist() {
        listOf("gpt-4o", "gpt-4.1-mini", "gpt-5.4", "gpt-99-relay", "gpt-5-codex",
            " OpenAI/GPT-5.4 ", "provider/team/gpt-5-mini:free").forEach {
            assertTrue(it, isGptSpeedModel(it))
        }
    }

    @Test fun rejectsOtherBrandsSubstringsAndNonTextModels() {
        listOf("", "gpt", "gpt-", "gpt-oss-120b", "o3", "openai/o3", "codex", "codex-mini",
            "foo-gpt-5", "provider/foo-gpt-5", "my gpt-5", "provider/gpt-5/chat",
            "gpt-image-1", "gpt-audio", "gpt-realtime", "gpt-4o-audio-preview",
            "openai/GPT-4O-REALTIME-PREVIEW", "gpt-4o-mini-tts", "gpt-4o-transcribe",
            "gpt-4o-mini-transcribe", "gpt-99-image-preview").forEach {
            assertFalse(it, isGptSpeedModel(it))
        }
    }
}
