package io.github.mangi.eta.agent.voice.tts

import android.app.Application
import android.content.Context
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReadAloudVoiceHistoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.getSharedPreferences("read_aloud_voice_history", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.initLocal(context)
    }
    @Test fun switchingModelsAndReturningRestoresEachVoice() {
        ReadAloudVoiceHistory.remember(context, "p", "model-a", "voice-a")
        assertEquals("", ReadAloudVoiceHistory.restore(context, "p", "model-b"))
        ReadAloudVoiceHistory.remember(context, "p", "model-b", "voice-b")
        assertEquals("voice-a", ReadAloudVoiceHistory.restore(context, "p", "model-a"))
        assertEquals("voice-b", ReadAloudVoiceHistory.restore(context, "p", "model-b"))
    }
    @Test fun sameModelIdOnDifferentProvidersNeverSharesVoice() {
        ReadAloudVoiceHistory.remember(context, "one", "tts", "one-voice")
        ReadAloudVoiceHistory.remember(context, "two", "tts", "two-voice")
        assertEquals("one-voice", ReadAloudVoiceHistory.restore(context, "one", "tts"))
        assertEquals("two-voice", ReadAloudVoiceHistory.restore(context, "two", "tts"))
    }
    @Test fun emptyTransientSelectionDoesNotEraseHistory() {
        ReadAloudVoiceHistory.remember(context, "p", "m", "selected")
        ReadAloudVoiceHistory.remember(context, "p", "m", "")
        ReadAloudVoiceHistory.remember(context, "", "m", "other")
        assertEquals("selected", ReadAloudVoiceHistory.restore(context, "p", "m"))
        assertEquals("", ReadAloudVoiceHistory.restore(context, "", "m"))
    }
    @Test fun personalVoiceIdentityIsPreservedWithoutPublicFallback() {
        for (voice in listOf("S_personal", "etaClonePersonal", "mimo-local-personal")) {
            ReadAloudVoiceHistory.remember(context, "p", "m", voice)
            assertEquals(voice, ReadAloudVoiceHistory.restore(context, "p", "m"))
        }
    }
    @Test fun compositeKeysCannotCollideAndArePersisted() {
        ReadAloudVoiceHistory.remember(context, "a:b", "c", "first")
        ReadAloudVoiceHistory.remember(context, "a", "b:c", "second")
        val anotherContext = context.createConfigurationContext(context.resources.configuration)
        assertEquals("first", ReadAloudVoiceHistory.restore(anotherContext, "a:b", "c"))
        assertEquals("second", ReadAloudVoiceHistory.restore(anotherContext, "a", "b:c"))
        assertEquals(2, context.getSharedPreferences("read_aloud_voice_history", Context.MODE_PRIVATE).all.size)
    }
    @Test fun existingGlobalChoiceIsMigratedBeforeExternalSelectionChanges() {
        Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID, "p")
        Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_ID, "old-model")
        Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, "old-voice")
        ReadAloudVoiceHistory.rememberCurrent(context)
        ReadAloudVoiceHistory.remember(context, "mimo", "new-model", "mimo-local-personal")
        assertEquals("old-voice", ReadAloudVoiceHistory.restore(context, "p", "old-model"))
        assertEquals("mimo-local-personal", ReadAloudVoiceHistory.restore(context, "mimo", "new-model"))
    }
}
