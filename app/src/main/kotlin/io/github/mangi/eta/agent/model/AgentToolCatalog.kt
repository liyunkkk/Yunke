package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.imagegen.ImageGenToolCatalog
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
        imageGenTools: Boolean = false,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        disabledTools: Set<String> = emptySet(),
    ): JSONArray =
        filterAdvertised(capabilities.project(JSONArray().also { tools ->
            AgentQuestionToolCatalog.appendTo(tools)
            AgentTodoToolCatalog.appendTo(tools)
            AgentContextAppToolCatalog.appendTo(tools)
            AgentSelfToolCatalog.appendTo(tools)
            AgentSpeechToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            if (imageGenTools) ImageGenToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools)
                AgentBrowserToolCatalog.appendTo(tools)
            }
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools)
            // 系统与一方应用操作：手电筒、音量、日历、闹钟、便签、系统开关。
            AgentPhoneToolCatalog.appendTo(tools, direct = true, reads = true, writes = true)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
            }
        }).let { filterEnabled(it, disabledTools) })

    fun filterEnabled(tools: JSONArray, disabledTools: Set<String>): JSONArray = JSONArray().also { result ->
        for (index in 0 until tools.length()) {
            val tool = tools.getJSONObject(index)
            if (Prefs.isToolEnabledInSet(tool.getJSONObject("function").getString("name"), disabledTools)) result.put(tool)
        }
    }

    fun allForSwitches(): JSONArray = build(
        true, true, true, true, true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true, imageGenTools = true,
        capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true, virtualDisplay = true),
    )

    fun legacyGroupToolNames(key: String): Set<String> {
        fun names(tools: JSONArray) = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        val all = names(allForSwitches())
        if (key !in setOf(Prefs.Keys.AGENT_TERMINAL_TOOLS, Prefs.Keys.AGENT_BROWSER_TOOLS, Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS, Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS, Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)) return emptySet()
        val without = names(build(
            terminalTools = key != Prefs.Keys.AGENT_TERMINAL_TOOLS,
            browserTools = key != Prefs.Keys.AGENT_BROWSER_TOOLS,
            deviceDirectTools = key != Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
            deviceSensitiveReadTools = key != Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            deviceSensitiveActionTools = key != Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true, virtualDisplay = true),
        ))
        return (all - without).map(Prefs::canonicalToolSwitchId).toSet()
    }

    /**
     * 轮询退避门禁挂起期从主代理目录里摘除 `get_task_result`。
     * 子代理工具由 `additionalTools` 追加，`AgentModelClient` 组装完整目录后仍需调用本方法兜底；
     * 门禁未安装或未挂起时原样返回，默认行为不变。
     */
    fun filterAdvertised(tools: JSONArray): JSONArray = SubAgentPollGuard.filterAdvertised(tools)
}
