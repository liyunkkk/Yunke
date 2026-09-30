package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSpeechToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "text_to_speech",
                description = "Speak text aloud with the app's TTS engine. Use when the user asks to read something, hear a voice, or get spoken output. Waits until the first sentence is audible (up to about 20s), then the rest plays in the background. status=speaking means audio is playing; status=pending means synthesis is still running and audibility is unconfirmed; SPEECH_CANCELLED or SPEECH_FAILED mean nothing was heard. Pass natural text without markdown. Do not put this in the visible reply.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "text",
                            JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 8_000)
                                .put("description", "The text to speak aloud."),
                        ),
                    )
                    .put("required", JSONArray().put("text")),
            ),
        )
    }
}
