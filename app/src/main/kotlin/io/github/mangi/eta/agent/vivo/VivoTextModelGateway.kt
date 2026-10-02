package io.github.mangi.eta.agent.vivo

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

/** Read the explicit selection only: never repair it or choose the first enabled model. */
internal object VivoTextModelGateway {
    data class Selection(val providerId: String?, val modelId: String?)

    // Configuration/OAuth errors propagate to the service's fixed-code error boundary.
    suspend fun selectedConfig(): AgentModelClient.ModelConfig? = resolve(
        readSelection = {
            val settings = ProviderRepository.settings()
            Selection(settings.selectedProviderId, settings.selectedModelId)
        },
        load = { provider, model -> RuntimeConfigRepository.configForProviderAndModel(provider, model) },
    )

    internal suspend fun resolve(
        readSelection: suspend () -> Selection,
        load: suspend (String, String) -> AgentModelClient.ModelConfig?,
    ): AgentModelClient.ModelConfig? {
        val selected = readSelection()
        val provider = selected.providerId?.takeIf { it.isNotBlank() } ?: return null
        val model = selected.modelId?.takeIf { it.isNotBlank() } ?: return null
        // Existing loader resolves OAuth in the Eta process and rejects disabled/missing entries.
        val config = load(provider, model) ?: return null
        if (config.providerId != provider || selected != readSelection()) return null
        return textOnly(config)
    }

    internal fun textOnly(config: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig? {
        if (config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) return null
        // Some provider implementations merge these AFTER their tool array. Do not silently
        // discard user configuration or allow it to reintroduce hosted tools/model overrides.
        if (config.customBody.isNotEmpty() || config.extraBodyJson.isNotBlank()) return null
        return AgentRuntimePolicy.withoutOptionalThinking(config).copy(
            systemPrompt = "", assistantId = "",
            hostedWebSearchEnabled = false,
            terminalTools = false, browserTools = false, deviceDirectTools = false,
            deviceSensitiveReadTools = false, deviceSensitiveActionTools = false,
            supportsVision = false, supportsVideo = false,
            summaryOutputLimit = 2048,
        )
    }
}
