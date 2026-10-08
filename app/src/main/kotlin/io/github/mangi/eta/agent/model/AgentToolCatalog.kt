package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.delegation.SubAgentPollGuard

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
    ): JSONArray =
        filterAdvertised(capabilities.project(JSONArray().also { tools ->
            AgentQuestionToolCatalog.appendTo(tools)
            AgentTodoToolCatalog.appendTo(tools)
            AgentContextAppToolCatalog.appendTo(tools)
            AgentSpeechToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) AgentBrowserToolCatalog.appendTo(tools)
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
            }
        }))

    /**
     * 轮询退避门禁挂起期从主代理目录里摘除 `get_task_result`。
     * 子代理工具由 `additionalTools` 追加，`AgentModelClient` 组装完整目录后仍需调用本方法兜底；
     * 门禁未安装或未挂起时原样返回，默认行为不变。
     */
    fun filterAdvertised(tools: JSONArray): JSONArray = SubAgentPollGuard.filterAdvertised(tools)
}
