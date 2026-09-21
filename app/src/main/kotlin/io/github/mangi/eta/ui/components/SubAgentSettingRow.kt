package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Flat setting row shared by model, role, tier and reasoning; no nested field outlines. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentSettingRow(
    label: String,
    value: String,
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    dropdown: Boolean = false,
    badge: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { contentDescription = description }
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, hapticFeedbackEnabled = false,
            onClick = onClick, onLongClick = onLongClick)
        .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = colors.primary.copy(alpha = alpha * 0.65f))
        Text(label, Modifier.widthIn(min = 72.dp, max = 100.dp), style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant.copy(alpha = alpha))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = colors.onSurface.copy(alpha = alpha))
            if (!badge.isNullOrBlank()) Surface(shape = RoundedCornerShape(6.dp),
                color = colors.secondaryContainer.copy(alpha = 0.45f)) {
                Text(badge, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = colors.onSecondaryContainer.copy(alpha = alpha))
            }
        }
        Icon(if (dropdown) Icons.Rounded.ExpandMore else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null, Modifier.size(18.dp), tint = colors.onSurfaceVariant.copy(alpha = alpha * 0.65f))
    }
}
