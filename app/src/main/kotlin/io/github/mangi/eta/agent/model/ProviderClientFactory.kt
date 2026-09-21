package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient {
        io.github.mangi.eta.data.model.RemovedProviderPolicy.requireSupported(
            config.baseUrl, config.openAiEndpointMode,
        )
        require(!io.github.mangi.eta.data.model.SpeechSynthesisModels.matches(config.model)) {
            "专用语音合成模型不能用于对话或摘要，请在朗读设置中配置"
        }
        val provider = when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> when (config.openAiEndpointMode) {
                OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
                else -> OpenAiChatCompletionsProvider
            }
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            else -> error("不支持的 Provider 协议类型：${config.providerType}")
        }
        return UsageRecordingProvider(provider)
    }
}
