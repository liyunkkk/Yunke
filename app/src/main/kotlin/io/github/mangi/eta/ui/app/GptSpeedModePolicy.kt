package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.isGptSpeedModel
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding

/** Effective main-agent speed projection, separate from reasoning depth and model credentials. */
internal object GptSpeedModePolicy {
    fun forBinding(current: GptSpeedMode, modelId: String, bindingAvailable: Boolean = true): GptSpeedMode =
        if (bindingAvailable && isGptSpeedModel(modelId)) current else GptSpeedMode.NORMAL

    fun cycle(current: GptSpeedMode, modelId: String, bindingAvailable: Boolean = true): GptSpeedMode =
        if (bindingAvailable && isGptSpeedModel(modelId)) current.next() else GptSpeedMode.NORMAL

    fun forSelection(
        current: GptSpeedMode,
        providerId: String,
        modelId: String,
        providers: List<ProviderSetting>,
    ): GptSpeedMode {
        val provider = providers.singleOrNull { it.id == providerId }?.takeIf { it.isEnabled }
        val model = provider?.models?.singleOrNull { it.id == modelId }?.takeIf { it.isEnabled }
        return forBinding(current, model?.modelId.orEmpty(), supportsGptSpeedBinding(provider, model))
    }

    fun snapshot(
        config: AgentModelClient.ModelConfig,
        mode: GptSpeedMode,
        bindingAvailable: Boolean = true,
    ): AgentModelClient.ModelConfig = config.copy(
        gptSpeedMode = mode.takeIf {
            bindingAvailable && isGptSpeedModel(config.model)
        },
    )
}
