package io.github.mangi.eta.ui.screens.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.SwitchPreference
import io.github.mangi.eta.ui.components.color
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
    // 保护开关请求进行中时禁用开关行（由 AgentAppRoot 的分发处持有并复位，
    // 见 staging README：pending 不能放屏内，失败回弹时状态值不变会导致屏内
    // LaunchedEffect(state) 不触发、开关永久禁用）。
    protectionPending: Boolean = false,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.ui_permission_health_3048bb),
        onBack = { onAction(PermissionHealthAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "title") {
            SmallTitle(stringResource(R.string.ui_permissions_and_status_35f368))
        }
        item(key = "card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                state.items.forEach { item ->
                    PermissionItemRow(
                        item = item,
                        onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                        onToggleChange = if (item.toggleChecked != null) {
                            { enabled -> onAction(PermissionHealthAction.ToggleAccessibilityProtection(enabled)) }
                        } else {
                            null
                        },
                        toggleEnabled = !protectionPending,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
    onToggleChange: ((Boolean) -> Unit)? = null,
    toggleEnabled: Boolean = true,
) {
    val icon = when (item.id) {
        "accessibility" -> Icons.Rounded.AccessibilityNew
        "accessibility_protection" -> Icons.Rounded.VerifiedUser
        "overlay" -> Icons.Rounded.Layers
        "model" -> Icons.Rounded.Memory
        "terminal" -> Icons.Rounded.Terminal
        "notification" -> Icons.Rounded.Notifications
        "root" -> Icons.Rounded.Key
        "shizuku" -> Icons.Rounded.Memory
        "xposed" -> Icons.Rounded.AccountTree
        "background" -> Icons.Rounded.History
        "app_list" -> Icons.Rounded.Dashboard
        "location" -> Icons.Rounded.LocationOn
        else -> Icons.Rounded.Shield
    }

    // 开关行：toggleChecked != null 时渲染 SwitchPreference（照旧开关写法），
    // checked 完全由状态驱动，不本地假切；其余行保持 ArrowPreference 不动。
    val toggleChecked = item.toggleChecked
    if (toggleChecked != null && onToggleChange != null) {
        SwitchPreference(
            title = item.title,
            summary = item.summary.takeIf { it.isNotBlank() },
            checked = toggleChecked,
            onCheckedChange = onToggleChange,
            startAction = {
                PreferenceIcon(icon = icon)
            },
            enabled = toggleEnabled,
        )
        return
    }

    ArrowPreference(
        title = item.title,
        summary = item.summary.takeIf { it.isNotBlank() },
        startAction = {
            PreferenceIcon(icon = icon)
        },
        endActions = {
            Text(
                text = item.status.label(),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = item.status.color(),
            )
        },
        onClick = onActionClick,
    )
}
