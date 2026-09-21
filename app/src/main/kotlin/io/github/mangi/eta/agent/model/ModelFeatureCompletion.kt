package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

/** Bounded one-shot requests. No tool execution, no fallback to a different provider. */
internal object ModelFeatureCompletion {
    fun complete(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        controller: AgentRunController,
        sessionId: String,
        timeoutMs: Long = 60_000,
        outputLimit: Int = 2048,
        providerOverride: AgentProviderClient? = null,
        usageConversationId: String = sessionId,
    ): String {
        val owner = Thread.currentThread()
        val child = AgentRunController()
        val binding = controller.register(interruptible = true) { child.cancel() }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val watchdog = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    if (owner.isInterrupted || controller.isCancelled || controller.isPaused ||
                        controller.hasPendingSteering || System.nanoTime() >= deadline) {
                        child.cancel()
                        break
                    }
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) { }
        }, "eta-model-feature-timeout").apply { isDaemon = true }
        try {
            require(timeoutMs > 0 && outputLimit > 0)
            require(config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()) { "辅助模型配置不完整" }
            controller.throwIfCancelled()
            watchdog.start()
            val requestConfig = io.github.mangi.eta.agent.runtime.AgentRuntimePolicy.withoutOptionalThinking(config).copy(
                hostedWebSearchEnabled = false,
                terminalTools = false, browserTools = false,
                deviceDirectTools = false, deviceSensitiveReadTools = false, deviceSensitiveActionTools = false,
                summaryOutputLimit = outputLimit,
            )
            val response = (providerOverride ?: ProviderClientFactory.getClient(requestConfig)).complete(
                ProviderRequest(requestConfig, messages, JSONArray(), sessionId, usageConversationId), child,
            ) {}
            controller.throwIfCancelled()
            check(!child.isCancelled && !owner.isInterrupted) { "辅助模型请求已取消或超时" }
            require(response.stopReason == AssistantStopReason.END_TURN) { "辅助模型未完整返回正文" }
            require((response.assistantMessage.optJSONArray("tool_calls")?.length() ?: 0) == 0) { "辅助模型返回了工具调用" }
            return response.assistantMessage.optString("content").trim()
                .also { require(it.isNotBlank() && it != "null") { "辅助模型返回了空正文" } }
        } finally {
            watchdog.interrupt()
            child.cancel()
            binding.close()
        }
    }
}
