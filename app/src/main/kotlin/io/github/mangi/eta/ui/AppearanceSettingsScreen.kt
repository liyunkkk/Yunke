package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
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
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import io.github.mangi.eta.ui.components.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
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

    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiAppearanceSettingsContent(
            appearance = appearance,
            onBack = onBack,
            uiStyles = uiStyles,
            uiStyleLabels = uiStyleLabels,
            themeModeLabels = themeModeLabels,
            foldLabels = foldLabels,
            onSelectUiStyle = ::selectUiStyle,
            onUpdate = ::update,
            onCommitScale = ::commitScale,
        )
        return
    }

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
                            keyPoints = listOf(80f, 90f, 100f, 110f),
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

    val parsedScale = scaleInput.toIntOrNull()
    WindowDialog(
        show = showScaleDialog,
        title = stringResource(R.string.appearance_interface_scale_dialog_title),
        summary = stringResource(R.string.appearance_interface_scale_dialog_summary),
        onDismissRequest = { showScaleDialog = false },
    ) {
        Column {
            TextField(
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.appearance_interface_scale_input_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                confirmEnabled = parsedScale != null && parsedScale in 80..110,
                onCancel = { showScaleDialog = false },
                onConfirm = {
                    parsedScale?.let { commitScale(it.toFloat()) }
                    showScaleDialog = false
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private val InterfaceScalePresets = listOf(80, 90, 100, 110)

@Composable
private fun CouiAppearanceSettingsContent(
    appearance: AppearanceSettings,
    onBack: () -> Unit,
    uiStyles: List<AppearanceUiStyle>,
    uiStyleLabels: List<String>,
    themeModeLabels: List<String>,
    foldLabels: List<String>,
    onSelectUiStyle: (AppearanceUiStyle) -> Unit,
    onUpdate: ((AppearanceSettings) -> AppearanceSettings) -> Unit,
    onCommitScale: (Float) -> Unit,
) {
    val themeModes = AppearanceThemeMode.entries
    val foldLevels = AnswerFoldLevel.entries
    val currentScale = (appearance.interfaceScale * 100f).roundToInt()
    val scaleLabels = InterfaceScalePresets.map { "$it%" }
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
            CouiDropdownPreference(
                title = stringResource(R.string.appearance_interface_scale),
                items = scaleLabels,
                selectedIndex = InterfaceScalePresets.indexOf(currentScale).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    InterfaceScalePresets.getOrNull(index)?.let { onCommitScale(it.toFloat()) }
                },
            )
        }

        CouiPageBottomSpacer()
    }
}
