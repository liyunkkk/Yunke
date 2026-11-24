package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.CouiSegmentedTabs
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.TabRowColors
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.InputField as MiuixInputField
import top.yukonga.miuix.kmp.basic.TabRow as MiuixTabRow
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference as MiuixOverlayDropdownPreference

/* 下拉选择行 / 搜索输入框 / 标签栏：参数与 Miuix 0.9.5-beta03 一致，COUI 风格下换成 COUI-Kit 对应组件。 */

@Composable
internal fun OverlayDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    renderInRootScaffold: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI && bottomAction == null) {
        CouiDropdownPreference(
            modifier = modifier,
            title = title,
            items = items,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { onSelectedIndexChange?.invoke(it) },
            summary = summary,
            enabled = enabled,
            icon = startAction,
        )
        return
    }
    MiuixOverlayDropdownPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        modifier = modifier,
        titleColor = titleColor,
        summary = summary,
        summaryColor = summaryColor,
        dropdownColors = dropdownColors,
        startAction = startAction,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        maxHeight = maxHeight,
        enabled = enabled,
        showValue = showValue,
        renderInRootScaffold = renderInRootScaffold,
        onExpandedChange = onExpandedChange,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
internal fun InputField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    textStyle: TextStyle? = null,
    color: Color = MiuixTheme.colorScheme.surfaceContainerHigh,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSearchBarBox(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = label,
            modifier = modifier,
            enabled = enabled,
            onSearch = { onSearch(query) },
        )
        return
    }
    MiuixInputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = onSearch,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        label = label,
        enabled = enabled,
        textStyle = textStyle,
        color = color,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        interactionSource = interactionSource,
    )
}

@Composable
internal fun TabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: TabRowColors = TabRowDefaults.tabRowColors(),
    minWidth: Dp = TabRowDefaults.TabRowMinWidth,
    maxWidth: Dp = TabRowDefaults.TabRowMaxWidth,
    height: Dp = TabRowDefaults.TabRowHeight,
    cornerRadius: Dp = TabRowDefaults.TabRowCornerRadius,
    itemSpacing: Dp = 9.dp,
    contentAlignment: Alignment = Alignment.Center,
    listState: LazyListState? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSegmentedTabs(
            items = tabs.mapIndexed { index, label -> index to label },
            selectedKey = selectedTabIndex,
            onSelectedKeyChange = onTabSelected,
            modifier = modifier,
        )
        return
    }
    MiuixTabRow(
        tabs = tabs,
        selectedTabIndex = selectedTabIndex,
        onTabSelected = onTabSelected,
        modifier = modifier,
        colors = colors,
        minWidth = minWidth,
        maxWidth = maxWidth,
        height = height,
        cornerRadius = cornerRadius,
        itemSpacing = itemSpacing,
        contentAlignment = contentAlignment,
        listState = listState,
        interactionSource = interactionSource,
        indication = indication,
    )
}
