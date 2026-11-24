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
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle

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
    /** Optional control rendered between the value and the trailing arrow; null keeps legacy rows unchanged. */
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val colors = MaterialTheme.colorScheme
    val couiColors = if (isCoui) CouiPageDefaults.themeColors(CouiTheme.isDark) else null
    val alpha = if (enabled) 1f else 0.38f
    val primaryTint = (couiColors?.title ?: colors.onSurface).copy(alpha = alpha)
    val secondaryTint = (couiColors?.summary ?: colors.onSurface).copy(alpha = alpha)
    val badgeShape = if (isCoui) CouiCapsuleShape else RoundedCornerShape(6.dp)
    val badgeColor = if (isCoui) {
        couiColors!!.accent.copy(alpha = 0.12f)
    } else {
        colors.surfaceVariant
    }
    val badgeTextColor = if (isCoui) {
        couiColors!!.accent.copy(alpha = alpha)
    } else {
        colors.onSurface.copy(alpha = alpha)
    }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { contentDescription = description }
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, hapticFeedbackEnabled = false,
            onClick = onClick, onLongClick = onLongClick)
        .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = secondaryTint)
        Text(label, Modifier.widthIn(min = 72.dp, max = 100.dp), style = MaterialTheme.typography.bodyMedium,
            color = primaryTint)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!badge.isNullOrBlank()) Surface(shape = badgeShape, color = badgeColor) {
                Text(badge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = badgeTextColor)
            }
            Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = if (isCoui) secondaryTint else primaryTint)
        }
        trailing?.invoke()
        Icon(if (dropdown) Icons.Rounded.ExpandMore else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            null, Modifier.size(18.dp), tint = secondaryTint)
    }
}
