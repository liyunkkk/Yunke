package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.itos.coui.theme.CouiPresets
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.ui.app.AppIconManager
import io.github.mangi.eta.ui.app.seedColor
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AnswerFoldLevel
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceAppIcon
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.MAX_INTERFACE_SCALE
import io.github.mangi.eta.data.model.MIN_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import io.github.mangi.eta.ui.components.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import io.github.mangi.eta.ui.components.TextButton
import io.github.mangi.eta.ui.components.Card
import top.yukonga.miuix.kmp.basic.Icon
import io.github.mangi.eta.ui.components.Slider
import io.github.mangi.eta.ui.components.Switch
import top.yukonga.miuix.kmp.basic.SliderDefaults
import io.github.mangi.eta.ui.components.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.TextField
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.OverlayDropdownPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import io.github.mangi.eta.ui.components.AdaptiveConfirmDialog

@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var scaleDraft by remember(appearance.interfaceScale) {
        mutableFloatStateOf(appearance.interfaceScale * 100f)
    }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    var morphLoadingExpanded by remember { mutableStateOf(false) }
    val blurSupported = isRuntimeShaderSupported()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update(transform)
        }
    }

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        update { current -> current.copy(interfaceScale = scale) }
    }

    fun selectUiStyle(uiStyle: AppearanceUiStyle) {
        coroutineScope.launch {
            AppearanceSettingsRepository.selectUiStyle(uiStyle)
        }
    }

    fun selectAppIcon(appIcon: AppearanceAppIcon) {
        update { current -> current.copy(appIcon = appIcon) }
        AppIconManager.apply(context, appIcon)
    }

    val appIcons = AppearanceAppIcon.entries
    val appIconLabels = appIcons.map { icon ->
        when (icon) {
            AppearanceAppIcon.PEARL -> stringResource(R.string.appearance_icon_pearl)
            AppearanceAppIcon.OBSIDIAN -> stringResource(R.string.appearance_icon_obsidian)
            AppearanceAppIcon.INDIGO -> stringResource(R.string.appearance_icon_indigo)
        }
    }

    val uiStyles = remember { AppearanceUiStyle.entries.sortedBy { it != AppearanceUiStyle.Default } }
    val uiStyleLabels = uiStyles.map { style ->
        when (style) {
            AppearanceUiStyle.MIUIX -> stringResource(R.string.appearance_ui_style_miuix)
            AppearanceUiStyle.COUI -> stringResource(R.string.appearance_ui_style_coui)
        }
    }
    val themeModes = AppearanceThemeMode.entries
    val themeModeLabels = listOf(
        stringResource(R.string.appearance_theme_system),
        stringResource(R.string.appearance_theme_light),
        stringResource(R.string.appearance_theme_dark),
    )
    val paletteStyles = AppearancePaletteStyle.entries
    val paletteLabels = listOf(
        stringResource(R.string.appearance_palette_tonal_spot),
        stringResource(R.string.appearance_palette_neutral),
        stringResource(R.string.appearance_palette_vibrant),
        stringResource(R.string.appearance_palette_expressive),
        stringResource(R.string.appearance_palette_rainbow),
        stringResource(R.string.appearance_palette_fruit_salad),
        stringResource(R.string.appearance_palette_monochrome),
        stringResource(R.string.appearance_palette_fidelity),
        stringResource(R.string.appearance_palette_content),
        stringResource(R.string.appearance_palette_cmf),
    )
    val accentColors = AppearanceAccentColor.entries
    val accentLabels = listOf(
        stringResource(R.string.appearance_accent_system),
        stringResource(R.string.appearance_accent_blue),
        stringResource(R.string.appearance_accent_purple),
        stringResource(R.string.appearance_accent_pink),
        stringResource(R.string.appearance_accent_red),
        stringResource(R.string.appearance_accent_orange),
        stringResource(R.string.appearance_accent_yellow),
        stringResource(R.string.appearance_accent_green),
        stringResource(R.string.appearance_accent_teal),
    )
    val foldLevels = AnswerFoldLevel.entries
    val foldLabels = listOf(
        stringResource(R.string.appearance_fold_level_aggressive),
        stringResource(R.string.appearance_fold_level_normal),
        stringResource(R.string.appearance_fold_level_off),
    )
    val blurStyles = AppearanceTopBarBlurStyle.entries
    val blurStyleLabels = listOf(
        stringResource(R.string.appearance_blur_style_gaussian),
        stringResource(R.string.appearance_blur_style_progressive),
    )
    val blurStyleSummaries = listOf(
        stringResource(R.string.appearance_blur_style_gaussian_summary),
        stringResource(R.string.appearance_blur_style_progressive_summary),
    )

    val minScalePercent = (MIN_INTERFACE_SCALE * 100f).roundToInt()
    val maxScalePercent = (MAX_INTERFACE_SCALE * 100f).roundToInt()

    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiAppearanceSettingsContent(
            appearance = appearance,
            onBack = onBack,
            uiStyles = uiStyles,
            uiStyleLabels = uiStyleLabels,
            themeModeLabels = themeModeLabels,
            accentColors = accentColors,
            accentLabels = accentLabels,
            appIcons = appIcons,
            appIconLabels = appIconLabels,
            foldLabels = foldLabels,
            scaleDraft = scaleDraft,
            onScaleDraftChange = { scaleDraft = it },
            onSelectUiStyle = ::selectUiStyle,
            onSelectAppIcon = ::selectAppIcon,
            onUpdate = ::update,
            onCommitScale = ::commitScale,
            onOpenScaleDialog = {
                scaleInput = scaleDraft.roundToInt().toString()
                showScaleDialog = true
            },
        )
    } else {
        MiuixScaffoldPage(
            title = stringResource(R.string.appearance_title),
            onBack = onBack,
        ) {
        item(key = "appearance_color_title") {
            SmallTitle(text = stringResource(R.string.appearance_group_color))
        }
        item(key = "appearance_color_card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_ui_style),
                    summary = uiStyleLabels[uiStyles.indexOf(appearance.uiStyle)],
                    items = uiStyleLabels,
                    selectedIndex = uiStyles.indexOf(appearance.uiStyle),
                    onSelectedIndexChange = { index ->
                        uiStyles.getOrNull(index)?.let(::selectUiStyle)
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_theme_mode),
                    summary = themeModeLabels[appearance.themeMode.ordinal],
                    items = themeModeLabels,
                    selectedIndex = appearance.themeMode.ordinal,
                    onSelectedIndexChange = { index ->
                        themeModes.getOrNull(index)?.let { mode ->
                            update { current -> current.copy(themeMode = mode) }
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_monet),
                    summary = stringResource(R.string.appearance_monet_summary),
                    checked = appearance.monetEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(monetEnabled = enabled) }
                    },
                )
                // 主题色在莫奈关闭时也生效（直接决定按钮、标签等强调色）。
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_accent_color),
                    summary = accentLabels[appearance.accentColor.ordinal],
                    items = accentLabels,
                    selectedIndex = appearance.accentColor.ordinal,
                    onSelectedIndexChange = { index ->
                        accentColors.getOrNull(index)?.let { accent ->
                            update { current -> current.copy(accentColor = accent) }
                        }
                    },
                )
                AnimatedVisibility(
                    visible = appearance.monetEnabled,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                Column {
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_palette_style),
                    summary = paletteLabels[appearance.paletteStyle.ordinal],
                    items = paletteLabels,
                    selectedIndex = appearance.paletteStyle.ordinal,
                    onSelectedIndexChange = { index ->
                        paletteStyles.getOrNull(index)?.let { style ->
                            update { current -> current.copy(paletteStyle = style) }
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_pure_black),
                    summary = stringResource(R.string.appearance_pure_black_summary),
                    checked = appearance.pureBlackEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(pureBlackEnabled = enabled) }
                    },
                )
                }
                }
                AppIconPickerPreference(
                    selected = appearance.appIcon,
                    appIcons = appIcons,
                    appIconLabels = appIconLabels,
                    accentColor = MiuixTheme.colorScheme.primary,
                    onSelect = ::selectAppIcon,
                )
            }
        }

        item(key = "appearance_interface_title") {
            SmallTitle(text = stringResource(R.string.appearance_group_interface))
        }
        item(key = "appearance_interface_card") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.appearance_message_timestamps),
                    summary = stringResource(R.string.appearance_message_timestamps_summary),
                    checked = appearance.messageTimestampsEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(messageTimestampsEnabled = enabled) }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_answer_fold),
                    summary = stringResource(R.string.appearance_answer_fold_summary),
                    items = foldLabels,
                    selectedIndex = appearance.answerFoldLevel.ordinal,
                    onSelectedIndexChange = { index ->
                        foldLevels.getOrNull(index)?.let { level ->
                            update { current -> current.copy(answerFoldLevel = level) }
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_blur),
                    summary = stringResource(R.string.appearance_blur_summary),
                    checked = appearance.blurEnabled && blurSupported,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(blurEnabled = enabled) }
                    },
                    enabled = blurSupported,
                )
                AnimatedVisibility(
                    visible = appearance.blurEnabled && blurSupported,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.appearance_blur_style),
                        summary = blurStyleSummaries[appearance.topBarBlurStyle.ordinal],
                        items = blurStyleLabels,
                        selectedIndex = appearance.topBarBlurStyle.ordinal,
                        onSelectedIndexChange = { index ->
                            blurStyles.getOrNull(index)?.let { style ->
                                update { current -> current.copy(topBarBlurStyle = style) }
                            }
                        },
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.appearance_morph_loading),
                    summary = when {
                        !appearance.morphLoadingIndicator ->
                            stringResource(R.string.appearance_morph_loading_summary)
                        appearance.morphLoadingBeforeResponseOnly ->
                            stringResource(R.string.appearance_morph_loading_before_response)
                        else ->
                            stringResource(R.string.appearance_morph_loading_during_generation)
                    },
                    onClick = {
                        if (appearance.morphLoadingIndicator) {
                            morphLoadingExpanded = !morphLoadingExpanded
                        } else {
                            update { current -> current.copy(morphLoadingIndicator = true) }
                            morphLoadingExpanded = true
                        }
                    },
                    holdDownState = appearance.morphLoadingIndicator && morphLoadingExpanded,
                    endActions = {
                        if (appearance.morphLoadingIndicator) {
                            Icon(
                                imageVector = if (morphLoadingExpanded) {
                                    Icons.Rounded.ExpandMore
                                } else {
                                    Icons.Rounded.ChevronRight
                                },
                                contentDescription = stringResource(
                                    if (morphLoadingExpanded) {
                                        R.string.appearance_morph_loading_collapse
                                    } else {
                                        R.string.appearance_morph_loading_expand
                                    },
                                ),
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(end = 6.dp)
                                    .size(16.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                        Switch(
                            checked = appearance.morphLoadingIndicator,
                            onCheckedChange = { enabled ->
                                update { current -> current.copy(morphLoadingIndicator = enabled) }
                                morphLoadingExpanded = enabled
                            },
                        )
                    },
                    bottomAction = if (appearance.morphLoadingIndicator && morphLoadingExpanded) {
                        {
                            SwitchPreference(
                                title = stringResource(R.string.appearance_morph_loading_before_response),
                                summary = stringResource(R.string.appearance_morph_loading_before_response_summary),
                                checked = appearance.morphLoadingBeforeResponseOnly,
                                onCheckedChange = { enabled ->
                                    update { current ->
                                        current.copy(morphLoadingBeforeResponseOnly = enabled)
                                    }
                                },
                                insideMargin = PaddingValues(0.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_swipe_dismiss),
                    summary = stringResource(R.string.appearance_swipe_dismiss_summary),
                    checked = appearance.swipeDismissEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(swipeDismissEnabled = enabled) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_predictive_back),
                    summary = stringResource(R.string.appearance_predictive_back_summary),
                    checked = appearance.predictiveBackEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(predictiveBackEnabled = enabled) }
                    },
                )
                if (blurSupported) {
                    SwitchPreference(
                        title = stringResource(R.string.appearance_progressive_blur),
                        summary = stringResource(R.string.appearance_progressive_blur_summary),
                        checked = appearance.progressiveBlurEnabled,
                        onCheckedChange = { enabled ->
                            update { current -> current.copy(progressiveBlurEnabled = enabled) }
                        },
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.appearance_interface_scale),
                    summary = stringResource(R.string.appearance_interface_scale_summary),
                    endActions = {
                        Text(
                            text = "${scaleDraft.roundToInt()}%",
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    bottomAction = {
                        Slider(
                            value = scaleDraft.coerceIn(
                                MIN_INTERFACE_SCALE * 100f,
                                MAX_INTERFACE_SCALE * 100f,
                            ),
                            onValueChange = { scaleDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                            onValueChangeFinished = { commitScale(scaleDraft) },
                            showKeyPoints = true,
                            keyPoints = listOf(70f, 85f, 100f, 115f, 130f),
                            magnetThreshold = 0.01f,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                        )
                    },
                    onClick = {
                        scaleInput = scaleDraft.roundToInt().toString()
                        showScaleDialog = true
                    },
                    holdDownState = showScaleDialog,
                )
            }
        }
        }
    }

    val parsedScale = scaleInput.toIntOrNull()
    AdaptiveConfirmDialog(
        show = showScaleDialog,
        title = stringResource(R.string.appearance_interface_scale_dialog_title),
        summary = stringResource(R.string.appearance_interface_scale_dialog_summary),
        onDismissRequest = { showScaleDialog = false },
        confirmText = stringResource(R.string.action_confirm),
        confirmEnabled = parsedScale != null && parsedScale in minScalePercent..maxScalePercent,
        onCancel = { showScaleDialog = false },
        onConfirm = {
            parsedScale?.let { commitScale(it.toFloat()) }
            showScaleDialog = false
        },
    ) {
        TextField(
            value = scaleInput,
            onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
            label = stringResource(R.string.appearance_interface_scale_input_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CouiAppearanceSettingsContent(
    appearance: AppearanceSettings,
    onBack: () -> Unit,
    uiStyles: List<AppearanceUiStyle>,
    uiStyleLabels: List<String>,
    themeModeLabels: List<String>,
    accentColors: List<AppearanceAccentColor>,
    accentLabels: List<String>,
    appIcons: List<AppearanceAppIcon>,
    appIconLabels: List<String>,
    foldLabels: List<String>,
    scaleDraft: Float,
    onScaleDraftChange: (Float) -> Unit,
    onSelectUiStyle: (AppearanceUiStyle) -> Unit,
    onSelectAppIcon: (AppearanceAppIcon) -> Unit,
    onUpdate: ((AppearanceSettings) -> AppearanceSettings) -> Unit,
    onCommitScale: (Float) -> Unit,
    onOpenScaleDialog: () -> Unit,
) {
    val themeModes = AppearanceThemeMode.entries
    val foldLevels = AnswerFoldLevel.entries
    val minPercent = MIN_INTERFACE_SCALE * 100f
    val maxPercent = MAX_INTERFACE_SCALE * 100f
    CouiScaffoldPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
    ) {
        Group(title = stringResource(R.string.appearance_group_color)) {
            CouiDropdownPreference(
                title = stringResource(R.string.appearance_ui_style),
                items = uiStyleLabels,
                selectedIndex = uiStyles.indexOf(appearance.uiStyle),
                onSelectedIndexChange = { index -> uiStyles.getOrNull(index)?.let(onSelectUiStyle) },
            )
            Divider()
            CouiDropdownPreference(
                title = stringResource(R.string.appearance_theme_mode),
                items = themeModeLabels,
                selectedIndex = appearance.themeMode.ordinal,
                onSelectedIndexChange = { index ->
                    themeModes.getOrNull(index)?.let { mode -> onUpdate { it.copy(themeMode = mode) } }
                },
            )
            Divider()
            CouiAccentColorPreference(
                selected = appearance.accentColor,
                accentColors = accentColors,
                accentLabels = accentLabels,
                onSelect = { accent ->
                    onUpdate { it.copy(accentColor = accent) }
                },
            )
            Divider()
            AppIconPickerPreference(
                selected = appearance.appIcon,
                appIcons = appIcons,
                appIconLabels = appIconLabels,
                accentColor = CouiTheme.colorScheme.accent,
                onSelect = onSelectAppIcon,
            )
        }

        Group(title = stringResource(R.string.appearance_group_interface)) {
            CouiSwitchPreference(
                title = stringResource(R.string.appearance_message_timestamps),
                summary = stringResource(R.string.appearance_message_timestamps_summary),
                checked = appearance.messageTimestampsEnabled,
                onCheckedChange = { enabled -> onUpdate { it.copy(messageTimestampsEnabled = enabled) } },
            )
            Divider()
            CouiDropdownPreference(
                title = stringResource(R.string.appearance_answer_fold),
                summary = stringResource(R.string.appearance_answer_fold_summary),
                items = foldLabels,
                selectedIndex = appearance.answerFoldLevel.ordinal,
                onSelectedIndexChange = { index ->
                    foldLevels.getOrNull(index)?.let { level -> onUpdate { it.copy(answerFoldLevel = level) } }
                },
            )
            Divider()
            CouiSwitchPreference(
                title = stringResource(R.string.appearance_morph_loading),
                summary = stringResource(R.string.appearance_morph_loading_summary),
                checked = appearance.morphLoadingIndicator,
                onCheckedChange = { enabled -> onUpdate { it.copy(morphLoadingIndicator = enabled) } },
            )
            Divider()
            CouiSwitchPreference(
                title = stringResource(R.string.appearance_swipe_dismiss),
                summary = stringResource(R.string.appearance_swipe_dismiss_summary),
                checked = appearance.swipeDismissEnabled,
                onCheckedChange = { enabled -> onUpdate { it.copy(swipeDismissEnabled = enabled) } },
            )
            Divider()
            CouiSwitchPreference(
                title = stringResource(R.string.appearance_predictive_back),
                summary = stringResource(R.string.appearance_predictive_back_summary),
                checked = appearance.predictiveBackEnabled,
                onCheckedChange = { enabled -> onUpdate { it.copy(predictiveBackEnabled = enabled) } },
            )
            Divider()
            CouiInterfaceScalePreference(
                scaleDraft = scaleDraft.coerceIn(minPercent, maxPercent),
                minPercent = minPercent,
                maxPercent = maxPercent,
                onScaleDraftChange = onScaleDraftChange,
                onCommitScale = onCommitScale,
                onOpenScaleDialog = onOpenScaleDialog,
            )
        }

        CouiPageBottomSpacer()
    }
}

@Composable
private fun AppIconPickerPreference(
    selected: AppearanceAppIcon,
    appIcons: List<AppearanceAppIcon>,
    appIconLabels: List<String>,
    accentColor: Color,
    onSelect: (AppearanceAppIcon) -> Unit,
) {
    val view = LocalView.current
    val selectedLabel = appIconLabels.getOrElse(selected.ordinal) { "" }
    val checkTint = if (accentColor.luminance() > 0.55f) Color(0xE6000000) else Color.White

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.appearance_icon),
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.appearance_icon_summary),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = selectedLabel,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            appIcons.forEachIndexed { index, icon ->
                val isSelected = icon == selected
                val label = appIconLabels.getOrElse(index) { icon.persistedValue }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) accentColor.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .clickable {
                            TouchHaptics.click(view)
                            onSelect(icon)
                        }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .then(
                                if (isSelected) {
                                    Modifier.border(2.dp, accentColor, RoundedCornerShape(18.dp))
                                } else {
                                    Modifier
                                },
                            )
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    width = 0.5.dp,
                                    color = Color(0x2E8892A8),
                                    shape = RoundedCornerShape(14.dp),
                                ),
                        ) {
                            Image(
                                painter = painterResource(AppIconManager.previewDrawableRes(icon)),
                                contentDescription = label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(accentColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = checkTint,
                                )
                            }
                        }
                    }
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) accentColor else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CouiAccentColorPreference(
    selected: AppearanceAccentColor,
    accentColors: List<AppearanceAccentColor>,
    accentLabels: List<String>,
    onSelect: (AppearanceAccentColor) -> Unit,
) {
    val view = LocalView.current
    val dark = CouiTheme.isDark
    val selectedLabel = if (selected == AppearanceAccentColor.SYSTEM) {
        "默认"
    } else {
        accentLabels.getOrElse(selected.ordinal) { "" }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "主题色",
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = selectedLabel,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            accentColors.forEach { accent ->
                val isSelected = accent == selected
                val swatchColor = if (accent == AppearanceAccentColor.SYSTEM) {
                    if (dark) CouiPresets.Dark.accent else CouiPresets.Light.accent
                } else {
                    accent.seedColor()
                }
                val checkTint = if (swatchColor.luminance() > 0.55f) Color(0xE6000000) else Color.White
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, swatchColor, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable {
                            TouchHaptics.click(view)
                            onSelect(accent)
                        }
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(
                                width = 0.5.dp,
                                color = if (dark) Color(0x33FFFFFF) else Color(0x1F000000),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = checkTint,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CouiInterfaceScalePreference(
    scaleDraft: Float,
    minPercent: Float,
    maxPercent: Float,
    onScaleDraftChange: (Float) -> Unit,
    onCommitScale: (Float) -> Unit,
    onOpenScaleDialog: () -> Unit,
) {
    val view = LocalView.current
    val currentPercent = scaleDraft.roundToInt()
    val accent = CouiTheme.colorScheme.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                TouchHaptics.click(view)
                onOpenScaleDialog()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.appearance_interface_scale),
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.appearance_interface_scale_summary),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (currentPercent != 100) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                TouchHaptics.click(view)
                                onCommitScale(100f)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "重置",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .clickable {
                            TouchHaptics.click(view)
                            onOpenScaleDialog()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "$currentPercent%",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
            }
        }
        Slider(
            value = scaleDraft,
            onValueChange = onScaleDraftChange,
            modifier = Modifier.fillMaxWidth(),
            valueRange = minPercent..maxPercent,
            onValueChangeFinished = { onCommitScale(scaleDraft) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${minPercent.roundToInt()}%",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "100%",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = "${maxPercent.roundToInt()}%",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
