package io.github.mangi.eta.data.model

/** Mutation reducers run against the latest row under the repository switch mutex. */
internal fun McpServerSetting.withToolSelection(names: Collection<String>, enabled: Boolean): McpServerSetting {
    val available = tools.map { it.name }.toSet()
    val selected = names.filter { it in available }.toSet()
    return copy(enabledToolNames = if (enabled) enabledToolNames + selected else enabledToolNames - selected)
}

internal fun McpServerSetting.withDiscoveredTools(discovered: McpServerSetting): McpServerSetting = copy(
    tools = discovered.tools,
    enabledToolNames = enabledToolNames.intersect(discovered.tools.map { it.name }.toSet()),
    lastRefreshedAt = discovered.lastRefreshedAt,
    lastProtocolVersion = discovered.lastProtocolVersion,
    toolsExpireAt = discovered.toolsExpireAt,
)
