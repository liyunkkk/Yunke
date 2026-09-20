package io.github.mangi.eta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

@Composable
internal fun TtsModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
    title: String,
) {
    if (!show) return
    val view = LocalView.current
    var expanded by remember { mutableStateOf(state.selectedModel?.providerId ?: state.providerGroups.singleOrNull()?.providerId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        tonalElevation = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (state.providerGroups.isEmpty()) Text(stringResource(R.string.provider_empty))
                state.providerGroups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val isExpanded = expanded == group.providerId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                TouchHaptics.click(view)
                                expanded = if (isExpanded) null else group.providerId
                            }.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.providerName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (isExpanded) "收起" else "展开")
                    }
                    if (isExpanded) Column(Modifier.selectableGroup()) {
                        group.models.forEach { model ->
                            val selected = state.selectedModel?.providerId == model.providerId && state.selectedModel?.id == model.id
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                        TouchHaptics.click(view)
                                        onModelSelected(model.providerId, model.id)
                                    }).padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(model.displayName, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
        },
    )
}
