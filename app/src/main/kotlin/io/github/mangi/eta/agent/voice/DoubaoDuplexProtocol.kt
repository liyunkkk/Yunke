package io.github.mangi.eta.agent.voice

import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/** API-key authentication must not be mixed with legacy TTS app/access headers. */
internal object DoubaoDuplexProtocol {
    // Never inherit a voice from another provider's ordinary TTS configuration.
    fun resolveVoice(configured: String): String = DoubaoRealtimeVoices.selectedId(configured)

    // Match the official web demo: ASR events are current hypotheses, not append-only text.
    fun eventText(event: JSONObject): String =
        listOf("text", "delta", "transcript", "content")
            .firstNotNullOfOrNull { key -> (event.opt(key) as? String)?.takeIf { it.isNotBlank() } }.orEmpty()

    // Output delta is append-only, unlike ASR hypotheses. Keep whitespace chunks intact.
    // The official demo accepts text, delta, transcript and content in that order.
    data class OutputText(val text: String, val field: Int)
    fun outputText(event: JSONObject): OutputText {
        listOf("text", "delta", "transcript", "content").forEachIndexed { index, key ->
            val value = event.opt(key)
            if (value is String && value.isNotEmpty()) return OutputText(value, index + 1)
        }
        return OutputText("", 0)
    }

    fun audioPayload(event: JSONObject): String =
        event.optString("audio").ifBlank { event.optString("delta") }

    fun request(apiKey: String): Request = Request.Builder()
        .url("wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue")
        .header("X-Api-Key", apiKey)
        .header("X-Api-Connect-Id", UUID.randomUUID().toString())
        .build()

    fun sessionCreate(voice: String, instructions: String): JSONObject = JSONObject()
        .put("type", "session.create")
        .put("event_id", UUID.randomUUID().toString())
        .put(
            "extension",
            JSONObject().put("asr", JSONObject().put("extra",
                JSONObject().put("enable_asr_twopass", true),
            )),
        )
        .put(
            "session",
            JSONObject()
                .put("model", "1.2.6.1")
                .put("instructions", instructions)
                .put(
                    "audio",
                    JSONObject()
                        .put(
                            "input",
                            JSONObject().put(
                                "format",
                                JSONObject().put("type", "pcm").put("rate", INPUT_RATE),
                            ),
                        )
                        .put(
                            "output",
                            JSONObject()
                                .put(
                                    "format",
                                    JSONObject().put("type", "pcm_s16le").put("rate", OUTPUT_RATE),
                                )
                                .put("voice", voice),
                        ),
                ),
        )

    private const val INPUT_RATE = 16_000
    // Official Python demo: output pcm is float32; pcm_s16le matches our PCM_16BIT AudioTrack.
    private const val OUTPUT_RATE = 24_000
}
