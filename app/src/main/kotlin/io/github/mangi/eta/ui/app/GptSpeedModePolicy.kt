package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.isGptSpeedModel

/** Transient conversation preference, separate from reasoning depth and model credentials. */
internal object GptSpeedModePolicy {
    fun forBinding(current: GptSpeedMode, modelId: String, eligibleProtocol: Boolean = true): GptSpeedMode =
        if (eligibleProtocol && isGptSpeedModel(modelId)) current else GptSpeedMode.NORMAL

    fun cycle(current: GptSpeedMode, modelId: String, eligibleProtocol: Boolean = true): GptSpeedMode =
        if (eligibleProtocol && isGptSpeedModel(modelId)) current.next() else GptSpeedMode.NORMAL

    fun snapshot(
        config: AgentModelClient.ModelConfig,
        mode: GptSpeedMode,
        eligibleTextModel: Boolean = true,
    ): AgentModelClient.ModelConfig = config.copy(
        gptSpeedMode = mode.takeIf {
            eligibleTextModel && config.providerType == ProviderTypes.OPENAI_COMPATIBLE &&
                isGptSpeedModel(config.model)
        },
    )
}
