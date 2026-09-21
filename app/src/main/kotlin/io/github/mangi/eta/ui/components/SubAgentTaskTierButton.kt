package io.github.mangi.eta.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** Shared light, outlined control; no filled gray tile or press ripple. */
@Composable
internal fun SubAgentChoiceField(
    label: String,
    value: String,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val textColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (enabled) 0.65f else 0.25f)),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .semantics { contentDescription = description }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) colors.onSurfaceVariant else textColor,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = textColor,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = textColor)
        }
    }
}

/** Selection is conveyed by a tonal background AND accessibility semantics, never a check icon. */
@Composable
internal fun SubAgentSelectionItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        .fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (selected) colors.secondaryContainer else Color.Transparent)
        .selectable(selected = selected, role = Role.RadioButton,
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface)
    }
}

@Composable
internal fun SubAgentTaskTierButton(
    label: String,
    tier: SubAgentTaskTier?,
    enabled: Boolean,
    onTierSelected: (SubAgentTaskTier) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelection by rememberUpdatedState(onTierSelected)
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier) {
        if (compact) {
            // 32dp visible chip with a 48dp hit area; no outlined container around the agent name.
            Box(Modifier.widthIn(min = 48.dp, max = 116.dp).heightIn(min = 48.dp)
                .semantics { contentDescription = "设置${label}任务分工" }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button,
                    onClick = { if (latestEnabled) { TouchHaptics.click(view); expanded = !expanded } }),
                contentAlignment = Alignment.Center) {
                Row(Modifier.clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (enabled) 0.65f else 0.25f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tier?.label ?: "设置分工", modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        } else SubAgentChoiceField(label, tier?.label ?: "未设置分工", enabled, if (label == "任务分工") "设置任务分工" else "设置${label}任务分工",
            onClick = { if (latestEnabled) { TouchHaptics.click(view); expanded = !expanded } })
        if (enabled) DropdownMenu(expanded, { expanded = false },
            modifier = Modifier.width(if (compact) 164.dp else 208.dp).selectableGroup(),
            shape = RoundedCornerShape(12.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp, shadowElevation = 3.dp) {
            SubAgentTaskTier.entries.forEach { option ->
                SubAgentSelectionItem(option.label, option == tier) {
                    if (latestEnabled) {
                        TouchHaptics.click(view)
                        latestSelection(option)
                        expanded = false
                    }
                }
            }
        }
    }
}
