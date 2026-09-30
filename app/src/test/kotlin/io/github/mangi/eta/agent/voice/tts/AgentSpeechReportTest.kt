package io.github.mangi.eta.agent.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSpeechReportTest {
    @Test fun agentSpeechSurvivesUiTeardownButOtherOwnersDoNot() {
        assertTrue(survivesUiTeardown(AGENT_SPEECH_OWNER))
        assertFalse(survivesUiTeardown("assistant-1"))
        assertFalse(survivesUiTeardown("tts-preview"))
        assertFalse(survivesUiTeardown(null))
    }

    @Test fun audibleIsReportedAsSpeaking() {
        val report = AgentSpeechReport()
        report.onAudible()
        val json = report.await(10).toJson()
        assertTrue(json.getBoolean("ok"))
        assertEquals("speaking", json.getString("status"))
        assertTrue(json.getBoolean("playing"))
    }

    @Test fun cancellationBeforeAudioCarriesTheStopReason() {
        val report = AgentSpeechReport()
        report.onFinished(SpeechOutcome.Cancelled("pause"))
        val json = report.await(10).toJson()
        assertFalse(json.getBoolean("ok"))
        assertEquals("SPEECH_CANCELLED", json.getString("code"))
        assertEquals("pause", json.getString("reason"))
    }

    @Test fun failureIsNeverReportedAsPlaying() {
        val report = AgentSpeechReport()
        report.onFinished(SpeechOutcome.Failed("朗读接口 HTTP 500"))
        val json = report.await(10).toJson()
        assertFalse(json.getBoolean("ok"))
        assertEquals("SPEECH_FAILED", json.getString("code"))
        assertFalse(json.has("playing"))
    }

    @Test fun firstDecisiveEventWins() {
        val report = AgentSpeechReport()
        report.onAudible()
        report.onFinished(SpeechOutcome.Cancelled("route_change"))
        assertEquals(AgentSpeechStatus.Speaking, report.await(10))
    }

    @Test fun shortTextCompletedBeforeReturnIsNotPlaying() {
        val report = AgentSpeechReport()
        report.onFinished(SpeechOutcome.Completed)
        val json = report.await(10).toJson()
        assertEquals("completed", json.getString("status"))
        assertFalse(json.getBoolean("playing"))
    }

    @Test fun timeoutIsPendingNotPlaying() {
        val json = AgentSpeechReport().await(20).toJson()
        assertEquals("pending", json.getString("status"))
        assertFalse(json.getBoolean("playing"))
    }

    @Test fun lateEventFromAnotherThreadWakesTheWaiter() {
        val report = AgentSpeechReport()
        Thread { Thread.sleep(50); report.onAudible() }.start()
        assertEquals(AgentSpeechStatus.Speaking, report.await(5_000))
    }
}
