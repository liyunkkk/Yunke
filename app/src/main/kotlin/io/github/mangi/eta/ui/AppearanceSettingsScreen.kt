package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.MAX_INTERFACE_SCALE
import io.github.mangi.eta.data.model.MIN_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import io.github.mangi.eta.ui.components.AppCatIcon
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.haptics.TouchHaptics
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
    var iconExpanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current
    val blurSupported = isRuntimeShaderSupported()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update(transform)
        }
    }

    fun confirmIcon(light: Int, dark: Int, cat: Int) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update { current ->
                current.copy(iconLightColor = light, iconDarkColor = dark, iconCatColor = cat)
            }
            val latest = AppearanceSettingsRepository.settings()
            LauncherIconSync.apply(context.applicationContext, latest)
        }
    }

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        update { current -> current.copy(interfaceScale = scale) }
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
    val blurStyles = AppearanceTopBarBlurStyle.entries
    val blurStyleLabels = listOf(
        stringResource(R.string.appearance_blur_style_gaussian),
        stringResource(R.string.appearance_blur_style_progressive),
    )
    val blurStyleSummaries = listOf(
        stringResource(R.string.appearance_blur_style_gaussian_summary),
        stringResource(R.string.appearance_blur_style_progressive_summary),
    )

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
                BasicComponent(
                    title = stringResource(R.string.appearance_icon),
                    summary = stringResource(R.string.appearance_icon_summary),
                    onClick = {
                        TouchHaptics.click(view)
                        iconExpanded = !iconExpanded
                    },
                    holdDownState = iconExpanded,
                    endActions = {
                        Icon(
                            imageVector = if (iconExpanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 16.dp).size(16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    bottomAction = if (iconExpanded) {
                        {
                            IconAppearanceEditor(
                                light = appearance.iconLightColor,
                                dark = appearance.iconDarkColor,
                                cat = appearance.iconCatColor,
                                onConfirm = ::confirmIcon,
                            )
                        }
                    } else {
                        null
                    },
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

private val iconSwatches = listOf(
    0xFFF27A1A, 0xFF7B61FF, 0xFFF26D9A, 0xFF3DDC84, 0xFFF5C542, 0xFF2491FF,
    0xFFF6F7F9, 0xFF1C1C1E,
).map { it.toInt() }

@Composable
private fun IconAppearanceEditor(
    light: Int,
    dark: Int,
    cat: Int,
    onConfirm: (Int, Int, Int) -> Unit,
) {
    var draftLight by remember(light) { androidx.compose.runtime.mutableIntStateOf(light) }
    var draftDark by remember(dark) { androidx.compose.runtime.mutableIntStateOf(dark) }
    var draftCat by remember(cat) { androidx.compose.runtime.mutableIntStateOf(cat) }
    val dirty = draftLight != light || draftDark != dark || draftCat != cat
    val view = LocalView.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LauncherIconPreview(background = draftLight, cat = draftCat)
            LauncherIconPreview(background = draftDark, cat = draftCat)
        }
        IconColorChoices(stringResource(R.string.appearance_icon_light), draftLight) { draftLight = it }
        IconColorChoices(stringResource(R.string.appearance_icon_dark), draftDark) { draftDark = it }
        IconColorChoices(stringResource(R.string.appearance_icon_cat), draftCat) { draftCat = it }
        BasicComponent(
            title = stringResource(R.string.appearance_icon_apply),
            summary = stringResource(R.string.appearance_icon_apply_summary),
            enabled = dirty,
            onClick = {
                if (!dirty) return@BasicComponent
                TouchHaptics.click(view)
                onConfirm(draftLight, draftDark, draftCat)
            },
        )
    }
}

@Composable
private fun IconColorChoices(title: String, selected: Int, onSelect: (Int) -> Unit) {
    val view = LocalView.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            iconSwatches.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(if (color == selected) 28.dp else 24.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .clickable {
                            TouchHaptics.click(view)
                            onSelect(color)
                        },
                )
            }
        }
    }
}

@Composable
private fun LauncherIconPreview(
    background: Int,
    cat: Int,
) {
    val context = LocalContext.current
    val name = LauncherIconSync.resourceName(background, cat)
    val id = context.resources.getIdentifier(name, "drawable", context.packageName)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp)),
    ) {
        if (id != 0) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}
