package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference as MiuixArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference as MiuixSwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference as MiuixWindowSpinnerPreference

@Composable
internal fun ArrowPreference(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    // COUI 风格下自动换成 COUI 设置行（带 bottomAction 的少数行没有 COUI 对应样式，仍走 Miuix）。
    if (LocalUiStyle.current == AppearanceUiStyle.COUI && bottomAction == null) {
        CouiPreference(
            modifier = modifier,
            title = title,
            summary = summary,
            enabled = enabled,
            onClick = onClick?.let { click ->
                {
                    TouchHaptics.click(view)
                    click()
                }
            },
            icon = startAction,
            showChevron = onClick != null,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) { endActions() }
            },
        )
        return
    }
    MiuixArrowPreference(
        title = title,
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = endActions,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        onClick = onClick?.let { click ->
            {
                TouchHaptics.click(view)
                click()
            }
        },
        holdDownState = holdDownState,
        enabled = enabled,
    )
}

@Composable
internal fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    // COUI 风格下自动换成 COUI 开关行。
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(
            modifier = modifier,
            title = title,
            checked = checked,
            onCheckedChange = onCheckedChange,
            summary = summary,
            enabled = enabled,
            icon = startAction,
        )
        return
    }
    MiuixSwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = { value ->
            TouchHaptics.click(view)
            onCheckedChange(value)
        },
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        insideMargin = insideMargin,
        holdDownState = holdDownState,
        enabled = enabled,
    )
}

@Composable
internal fun WindowSpinnerPreference(
    items: List<DropdownItem>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    spinnerColors: DropdownColors = DropdownDefaults.dropdownColors(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    val view = LocalView.current
    MiuixWindowSpinnerPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        spinnerColors = spinnerColors,
        startAction = startAction,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        maxHeight = maxHeight,
        enabled = enabled,
        showValue = showValue,
        onExpandedChange = { expanded ->
            if (expanded) TouchHaptics.click(view)
            onExpandedChange?.invoke(expanded)
        },
        onSelectedIndexChange = { index ->
            TouchHaptics.click(view)
            onSelectedIndexChange?.invoke(index)
        },
    )
}

@Composable
internal fun HapticBasicComponent(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    BasicComponent(
        title = title,
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        startAction = startAction,
        endActions = endActions,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        onClick = onClick?.let { click ->
            {
                TouchHaptics.click(view)
                click()
            }
        },
        holdDownState = holdDownState,
        enabled = enabled,
    )
}
