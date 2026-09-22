package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Non-empty terminal arguments replace the stream as-is. Blank or absent input keeps the stream; explicit JSON null remains invalid input. */
internal object ToolArguments {
    fun merge(existing: String, incoming: Any?): String {
        val next = text(incoming)
        if (next.isBlank()) return existing
        return next
    }

    private fun text(value: Any?): String = when (value) {
        null -> ""
        JSONObject.NULL -> "null"
        is String -> value
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }
}
