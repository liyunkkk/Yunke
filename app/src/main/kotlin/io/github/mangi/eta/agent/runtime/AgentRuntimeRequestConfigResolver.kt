package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient

internal object AgentRuntimeRequestConfigResolver {
    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE

    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
        // 浮窗（eta_voice 入口）在 App 进程发起请求时，assistantId 取自 App 侧配置，
        // 而该配置来自只读 remote preferences —— Eta 自身进程恒不可用，会退化成空串。
        // Runtime 侧这里已经解析出真实 config（含 active 助手 id），同步补齐该字段，
        // 否则 RunExecutor 的 requireNotNull 会抛「任务所属助手不存在」。
        val resolvedAssistantId = request.assistantId.ifBlank { config.assistantId }
        val handoff = request.handoff
            ?: return request.copy(config = config, assistantId = resolvedAssistantId)
        val archivePayload = AgentExternalArchivePayload.from(handoff.payload)
        return request.copy(
            config = config,
            assistantId = resolvedAssistantId,
            handoff = archivePayload?.let { payload ->
                handoff.copy(
                    payload = payload.copy(
                        thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                        reasoningEffort = config.effectiveReasoningEffort,
                    ).toJson(),
                )
            } ?: handoff,
        )
    }
}
