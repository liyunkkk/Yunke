package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.SharedPreferences
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import io.github.mangi.eta.config.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.components.SwitchPreference
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.components.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow

private object ToolsMetrics {
    val GridHorizontalPadding = 20.dp
    val GridGap = 12.dp
}

@Composable
fun AgentToolsScreen(
    state: AgentToolsUiState,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val capabilities = rememberDeviceCapabilities()
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    var disabled by remember { mutableStateOf(Prefs.disabledTools()) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val prefs = Prefs.localAgentPreferences()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_DISABLED_TOOLS_JSON) disabled = Prefs.disabledTools()
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun setEnabled(names: Collection<String>, enabled: Boolean) {
        if (saving) return
        saving = true
        disabled = disabled.toMutableSet().apply {
            names.forEach { if (enabled) remove(Prefs.canonicalToolSwitchId(it)) else add(Prefs.canonicalToolSwitchId(it)) }
        }
        scope.launch {
            try { withContext(Dispatchers.IO) { Prefs.setToolsEnabled(names, enabled) } }
            finally { disabled = Prefs.disabledTools(); saving = false }
        }
    }
    val groups = projectToolGroups(AgentToolsCatalogUi.complete(state.groups), showAll, capabilities.root.isGranted, capabilities.tools.colorOs)
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_tool_ability_9f0f80),
        onBack = { onAction(AgentToolsAction.NavigateBack) },
        modifier = modifier,
        listState = if (showAll) allListState else currentListState,
    ) {
        item(key = "capability-view") {
            TabRow(
                tabs = listOf(stringResource(R.string.capability_current_device), stringResource(R.string.capability_all)),
                selectedTabIndex = if (showAll) 1 else 0,
                onTabSelected = { showAll = it == 1 },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        groups.forEach { group ->
            item(key = "${group.id}-title") {
                SwitchPreference(
                    title = group.title,
                    summary = "${group.tools.count { Prefs.isToolEnabledInSet(it.id, disabled) }} / ${group.tools.size} 已开启",
                    checked = group.tools.all { Prefs.isToolEnabledInSet(it.id, disabled) },
                    enabled = !saving,
                    onCheckedChange = { setEnabled(group.tools.map { it.id }, it) },
                )
            }
            items(
                items = group.tools.chunked(2),
                key = { row -> "${group.id}-${row.joinToString(separator = "-") { it.id }}" },
            ) { row ->
                ToolGridRow(
                    tools = row,
                    rootGranted = capabilities.root.isGranted,
                    capabilities = capabilities.tools,
                    onAction = onAction,
                    disabled = disabled,
                    saving = saving,
                    onToggle = { name, enabled -> setEnabled(listOf(name), enabled) },
                )
            }
        }
    }
}

@Composable
private fun ToolGridRow(
    tools: List<ToolItemUi>,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    disabled: Set<String>,
    saving: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ToolsMetrics.GridHorizontalPadding)
            .padding(bottom = ToolsMetrics.GridGap),
    ) {
        val useSingleColumn = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f
        if (useSingleColumn) {
            Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap)) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onAction = onAction,
                        enabled = Prefs.isToolEnabledInSet(tool.id, disabled),
                        saving = saving,
                        onToggle = { onToggle(tool.id, it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap),
            ) {
                tools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        rootGranted = rootGranted,
                        capabilities = capabilities,
                        onAction = onAction,
                        enabled = Prefs.isToolEnabledInSet(tool.id, disabled),
                        saving = saving,
                        onToggle = { onToggle(tool.id, it) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (tools.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
