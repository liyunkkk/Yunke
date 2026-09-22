package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelFailure

/** Provider outages are distinct from a child that examined the task and failed. */
internal object SubAgentProviderFailure {
    fun find(error: Throwable): AgentModelFailure? {
        val seen = HashSet<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            if (current is AgentModelFailure && isUnavailable(current.code)) return current
            current = current.cause
        }
        return null
    }

    fun isUnavailable(code: String): Boolean = when {
        code == "CONTEXT_WINDOW_EXCEEDED" || code == "HTTP_400" -> false
        code == "MODEL_TIMEOUT" || code == "MODEL_CONNECTION_FAILED" || code == "STREAM_INCOMPLETE" -> true
        code.startsWith("HTTP_") -> code.removePrefix("HTTP_").toIntOrNull()?.let { it != 400 } ?: true
        else -> false
    }
}
