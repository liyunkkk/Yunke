package io.github.mangi.eta.agent.voice.tts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Longest a `text_to_speech` call waits for the first audible sentence before reporting it as pending. */
internal const val AGENT_SPEECH_START_TIMEOUT_MS = 20_000L

/** What the Agent tool can truthfully report about one playback. */
internal sealed interface AgentSpeechStatus {
    fun toJson(): JSONObject

    /** First sentence is playing; later sentences continue in the background. */
    data object Speaking : AgentSpeechStatus {
        override fun toJson(): JSONObject = JSONObject().put("ok", true).put("status", "speaking").put("playing", true)
    }

    /** Short text that finished before this call returned. */
    data object Completed : AgentSpeechStatus {
        override fun toJson(): JSONObject = JSONObject().put("ok", true).put("status", "completed").put("playing", false)
    }

    /** Still synthesising when the wait ended; audibility is unconfirmed. */
    data object Pending : AgentSpeechStatus {
        override fun toJson(): JSONObject = JSONObject().put("ok", true).put("status", "pending").put("playing", false)
            .put("message", "语音仍在合成，尚未确认出声")
    }

    data class Failed(val message: String) : AgentSpeechStatus {
        override fun toJson(): JSONObject = JSONObject().put("ok", false).put("code", "SPEECH_FAILED").put("message", message)
    }

    data class Cancelled(val reason: String) : AgentSpeechStatus {
        override fun toJson(): JSONObject = JSONObject().put("ok", false).put("code", "SPEECH_CANCELLED")
            .put("reason", reason).put("message", "朗读在出声前被停止")
    }
}

/**
 * Bridges main-thread playback callbacks to the blocking tool thread. The first decisive event wins:
 * audible, finished, or the wait timing out.
 */
internal class AgentSpeechReport : SpeechPlaybackListener {
    @Volatile private var status: AgentSpeechStatus? = null
    private val decided = CountDownLatch(1)

    override fun onAudible() = decide(AgentSpeechStatus.Speaking)

    override fun onFinished(outcome: SpeechOutcome) = decide(
        when (outcome) {
            SpeechOutcome.Completed -> AgentSpeechStatus.Completed
            is SpeechOutcome.Failed -> AgentSpeechStatus.Failed(outcome.message)
            is SpeechOutcome.Cancelled -> AgentSpeechStatus.Cancelled(outcome.reason)
        },
    )

    @Synchronized
    private fun decide(next: AgentSpeechStatus) {
        if (status != null) return
        status = next
        decided.countDown()
    }

    fun await(timeoutMs: Long): AgentSpeechStatus {
        val done = try {
            decided.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return if (done) status ?: AgentSpeechStatus.Pending else AgentSpeechStatus.Pending
    }
}
