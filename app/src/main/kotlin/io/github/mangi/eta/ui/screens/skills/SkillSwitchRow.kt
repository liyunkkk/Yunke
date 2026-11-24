package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.components.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import io.github.mangi.eta.ui.components.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.Role
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Item
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiIcon

@Composable
internal fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit = {},
    onDelete: (() -> Unit)? = null,
    showSwitch: Boolean = true,
    showMenu: Boolean = true,
) {
    var showDescription by remember(skill.id) { mutableStateOf(false) }
    val description = skill.description.ifBlank { stringResource(R.string.skills_no_description) }
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSkillSwitchRow(
            skill = skill,
            enabled = enabled,
            description = description,
            onToggle = onToggle,
            onDelete = onDelete,
            showSwitch = showSwitch,
            showMenu = showMenu,
            onShowDescription = { showDescription = true },
        )
    } else {
        BasicComponent(
            startAction = { PreferenceIcon(iconForSkill(skill.id), enabled = enabled) },
            endActions = {
                if (showMenu) {
                    OverlayIconDropdownMenu(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        entry = DropdownEntry(
                            items = listOfNotNull(
                                DropdownItem(
                                    text = stringResource(R.string.ui_view_description),
                                    onClick = { showDescription = true },
                                ),
                                onDelete?.let { delete ->
                                    DropdownItem(
                                        text = stringResource(R.string.ui_delete_3755f5),
                                        enabled = enabled,
                                        onClick = { if (enabled) delete() },
                                    )
                                },
                            ),
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = stringResource(R.string.skills_more_named, skill.name),
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                }
                if (showSwitch) {
                    if (showMenu) Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = onToggle,
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            },
        ) {
            Text(
                text = skill.name,
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSurface,
            )
            Text(
                text = description,
                style = MiuixTheme.textStyles.body2,
                color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    ItemDescriptionDialog(
        show = showDescription,
        title = skill.name,
        description = description,
        onDismiss = { showDescription = false },
    )
}

internal fun iconForSkill(skillId: String): ImageVector = when (skillId) {
    "self-improving-agent" -> Icons.Rounded.Refresh
    "skill-creator" -> Icons.Rounded.DesignServices
    else -> Icons.Rounded.Extension
}

@Composable
private fun CouiSkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    description: String,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    showSwitch: Boolean,
    showMenu: Boolean,
    onShowDescription: () -> Unit,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val destructiveColor = CouiDialogDefaults.themeColors(dark).destructive
    var menuExpanded by remember { mutableStateOf(false) }
    val anchor = rememberCouiMenuAnchor()
    val viewDescText = stringResource(R.string.ui_view_description)
    val deleteText = stringResource(R.string.ui_delete_3755f5)
    val menuItems = remember(viewDescText, deleteText, onDelete, enabled, destructiveColor) {
        buildList {
            add(CouiMenuItem(value = 0, text = viewDescText))
            if (onDelete != null) {
                add(
                    CouiMenuItem(
                        value = 1,
                        text = deleteText,
                        enabled = enabled,
                        iconTint = destructiveColor,
                        dividerBefore = true,
                    ),
                )
            }
        }
    }
    Item(
        enabled = enabled,
        onClick = { if (showSwitch) onToggle(!skill.enabled) else onShowDescription() },
        title = {
            CouiText(
                text = skill.name,
                kind = CouiTextKind.ItemTitle,
                color = if (enabled) colors.title else colors.disabled,
            )
        },
        summary = {
            CouiText(
                text = description,
                kind = CouiTextKind.ItemSummary,
                color = if (enabled) colors.summary else colors.disabled,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            if (showMenu) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .couiMenuAnchor(anchor)
                            .clickable(role = Role.Button) {
                                anchor.open { menuExpanded = true }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        CouiIcon(
                            imageVector = CouiIcons.Outlined.More,
                            contentDescription = stringResource(R.string.skills_more_named, skill.name),
                            modifier = Modifier.size(20.dp),
                            tint = colors.summary,
                        )
                    }
                    Menu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        items = menuItems,
                        onItemClick = { item ->
                            menuExpanded = false
                            when (item.value) {
                                0 -> onShowDescription()
                                1 -> if (enabled) onDelete?.invoke()
                            }
                        },
                        anchor = anchor,
                    )
                }
            }
            if (showSwitch) {
                if (showMenu) Spacer(modifier = Modifier.width(8.dp))
                CouiSwitch(
                    checked = skill.enabled,
                    onCheckedChange = onToggle,
                    dark = dark,
                    colors = CouiSwitchDefaults.colors(colors.accent, dark),
                    enabled = enabled,
                )
            }
        },
    )
}
