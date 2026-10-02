package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentPromptForecast

/** A display-only prediction. Runtime already counted history, tools and system prompts. */
internal fun nextRequestContextTokens(
    runtimeForecastTokens: Int?,
    draftTokens: Int,
    billedContextTokens: Int?,
    fallbackContextTokens: Int?,
): Int? {
    // Before the first real receipt, the main ring already displays the cold estimate.
    if (billedContextTokens == null || billedContextTokens <= 0) return null
    val runtime = runtimeForecastTokens?.takeIf { it > 0 }
    return if (runtime != null) {
        AgentPromptForecast.project(runtime, draftTokens.coerceAtLeast(0).toLong(), 0L)
    } else {
        // The fallback is already the full calibrated next request, including the draft.
        fallbackContextTokens?.takeIf { it > 0 }
    }
}
