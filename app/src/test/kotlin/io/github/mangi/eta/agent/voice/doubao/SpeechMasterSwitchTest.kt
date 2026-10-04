package io.github.mangi.eta.agent.voice.doubao

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.voice.SpeechInputSession
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SpeechMasterSwitchTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun conversationRemainsReadyWhenDictationIsOff() {
        DoubaoVoiceConfig.save(context, DoubaoVoiceConfig.Config(
            cloudAsr = true, asrKey = "test", inputEnabled = false,
            conversationEnabled = true, duplexEnabled = false,
            conversationCloudAsr = true, conversationAsrKey = "conversation-test",
        ))
        DoubaoVoiceConfig.load(context)
        assertFalse(SpeechInputSession.ready())
        assertTrue(SpeechInputSession.ready(io.github.mangi.eta.agent.voice.VoiceEntryMode.UNIVERSAL))
        assertFalse(DoubaoVoiceConfig.state.value.duplexEnabled)
    }
    @Test fun independentSwitchesPersistWithoutClearingCredentials() {
        DoubaoVoiceConfig.save(context, DoubaoVoiceConfig.Config(
            cloudAsr = true, asrKey = "test", cloneKey = "clone",
            inputEnabled = false, conversationEnabled = false, duplexEnabled = false,
        ))
        DoubaoVoiceConfig.load(context)
        val config = DoubaoVoiceConfig.state.value
        assertFalse(config.inputEnabled)
        assertFalse(config.conversationEnabled)
        assertFalse(config.duplexEnabled)
        assertEquals("test", config.asrKey)
        assertEquals("clone", config.cloneKey)
        assertTrue(io.github.mangi.eta.agent.voice.VoiceEntryPolicy.modes(config).isEmpty())
    }

    @Test fun enabledCloudMigratesButExplicitOffSurvivesReloadAndEngineSelection() {
        context.getSharedPreferences("doubao_voice", Context.MODE_PRIVATE).edit().putBoolean("asr", true).putString("asr_key", "test").commit()
        DoubaoVoiceConfig.load(context)
        assertTrue(SpeechInputSession.ready())
        DoubaoVoiceConfig.save(context, DoubaoVoiceConfig.state.value.copy(inputEnabled = false))
        DoubaoVoiceConfig.load(context)
        assertFalse(SpeechInputSession.ready())
        assertTrue(DoubaoVoiceConfig.state.value.cloudAsr)
        assertEquals("test", DoubaoVoiceConfig.state.value.asrKey)
        DoubaoVoiceConfig.save(context, DoubaoVoiceConfig.state.value.copy(cloudAsr = false))
        assertFalse(DoubaoVoiceConfig.state.value.inputEnabled)
    }
    @Test fun offlineEnabledMigratesAndFreshInstallStaysOff() {
        DoubaoVoiceConfig.load(context)
        assertFalse(DoubaoVoiceConfig.state.value.inputEnabled)
        reset()
        context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        DoubaoVoiceConfig.load(context)
        assertTrue(DoubaoVoiceConfig.state.value.inputEnabled)
        assertFalse(DoubaoVoiceConfig.state.value.cloudAsr)
    }
}
