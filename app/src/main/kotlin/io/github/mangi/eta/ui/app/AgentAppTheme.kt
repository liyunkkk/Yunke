package io.github.mangi.eta.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme as materialDarkColorScheme
import androidx.compose.material3.lightColorScheme as materialLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.text.CouiTextKind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.ui.haptics.ApplyTouchHapticFeedbackEnabled
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.theme.CouiPresets
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiDarkTheme
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.components.DemandSceneBackdrop
import io.github.mangi.eta.ui.components.EtaTopLayerHost
import io.github.mangi.eta.ui.components.EtaTopLayerState
import io.github.mangi.eta.ui.components.captureGlassBackdrop
import io.github.mangi.eta.ui.components.rememberGlassBackdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.platformDynamicColors
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean = false,
    provideGlassHost: Boolean = false,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    systemOverlay: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val colorSchemeMode = when {
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.Light
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.Dark
        !appearance.monetEnabled -> ColorSchemeMode.System
        appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    val systemSeedColor = if (
        appearance.monetEnabled &&
        appearance.accentColor == AppearanceAccentColor.SYSTEM &&
        appearance.paletteStyle != AppearancePaletteStyle.TONAL_SPOT
    ) {
        platformDynamicColors(isDark).primary
    } else {
        null
    }
    val keyColor = when {
        !appearance.monetEnabled -> null
        appearance.accentColor == AppearanceAccentColor.SYSTEM -> systemSeedColor
        else -> appearance.accentColor.seedColor()
    }
    val controller = remember(appearance, colorSchemeMode, keyColor, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = appearance.paletteStyle.toMiuixPaletteStyle(),
            isDark = isDark,
        )
    }

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    val currentContent by rememberUpdatedState(content)
    // 切换 UI 样式时移动同一内容树，保留导航栈、页面草稿与正在运行的任务状态。
    val currentIsDark by rememberUpdatedState(isDark)
    val scopedContent = remember {
        movableContentOf {
            // MaterialTheme 仅向 markdown-renderer-m3 等 Material 组件提供与当前样式一致的颜色上下文。
            MaterialTheme(colorScheme = miuixBackedMaterialColors(currentIsDark)) {
                ApplyTouchHapticFeedbackEnabled()
                currentContent()
            }
        }
    }

    MiuixTheme(controller = controller) {
        val baseMiuixColors = MiuixTheme.colorScheme
        val pureBlack = appearance.monetEnabled && appearance.pureBlackEnabled && isDark
        // 莫奈关闭且手动选了主题色时，按钮/标签/强调文字用所选颜色（Miuix 与 COUI 都生效）；
        // 选“跟随系统”时保持各自默认蓝。背景、卡片、文字等中性色不变。
        val customAccent: Color? = if (!appearance.monetEnabled && appearance.accentColor != AppearanceAccentColor.SYSTEM) {
            appearance.accentColor.seedColor().let { seed -> if (isDark) lerp(seed, Color.White, 0.28f) else seed }
        } else {
            null
        }
        val customOnAccent: Color? = customAccent?.let { if (it.luminance() > 0.5f) Color(0xE6000000) else Color.White }
        val couiPreset = if (isDark) CouiPresets.Dark else CouiPresets.Light
        val couiNative = if (customAccent != null && customOnAccent != null) {
            couiPreset.copy(
                accent = customAccent,
                onAccent = customOnAccent,
                accentOnSurface = customAccent,
                checkboxAccent = customAccent,
                menuCheckboxAccent = customAccent,
            )
        } else {
            couiPreset
        }
        val themedMiuixColors = remember(
            baseMiuixColors,
            appearance.uiStyle,
            pureBlack,
            isDark,
            customAccent,
        ) {
            when {
                appearance.uiStyle == AppearanceUiStyle.COUI -> {
                    val accentContainer = couiNative.accent.copy(alpha = if (isDark) 0.22f else 0.12f)
                    baseMiuixColors.copy(
                        primary = couiNative.accent,
                        onPrimary = couiNative.onAccent,
                        primaryContainer = accentContainer,
                        onPrimaryContainer = couiNative.accent,
                        secondaryContainer = if (isDark) Color(0xFF282A2E) else Color(0xFFECEEF1),
                        onSecondaryContainer = couiNative.onSurface,
                        tertiaryContainer = accentContainer,
                        onTertiaryContainer = couiNative.accent,
                        background = couiNative.background,
                        surface = if (isDark) Color(0xFF121214) else Color.White,
                        surfaceContainer = if (isDark) Color(0xFF1E2024) else Color(0xFFF4F5F7),
                        surfaceContainerHigh = if (isDark) Color(0xFF282A2E) else Color(0xFFEBECEE),
                        surfaceContainerHighest = if (isDark) Color(0xFF32353A) else Color(0xFFE2E4E8),
                        onBackground = couiNative.onSurface,
                        onSurface = couiNative.onSurface,
                        onSurfaceContainer = couiNative.onSurface,
                        onSurfaceVariantSummary = couiNative.onSurfaceVariant,
                        onSurfaceVariantActions = couiNative.onSurfaceVariant,
                        outline = if (isDark) Color(0xFF3A3C42) else Color(0xFFDCDFE4),
                        dividerLine = couiNative.outlineVariant,
                    )
                }
                pureBlack -> baseMiuixColors.copy(
                    background = Color.Black,
                    surface = Color.Black,
                )
                else -> baseMiuixColors
            }.let { colors ->
                // Miuix 风格下同样套用所选主题色（COUI 分支已在 couiNative 里处理）。
                if (customAccent != null && customOnAccent != null && appearance.uiStyle != AppearanceUiStyle.COUI) {
                    val container = customAccent.copy(alpha = if (isDark) 0.22f else 0.12f)
                    colors.copy(
                        primary = customAccent,
                        onPrimary = customOnAccent,
                        primaryContainer = container,
                        onPrimaryContainer = customAccent,
                        tertiaryContainer = container,
                        onTertiaryContainer = customAccent,
                    )
                } else {
                    colors
                }
            }
        }

        val blurSupported = isRuntimeShaderSupported()

        val platformDensity = LocalDensity.current
        val appDensity = remember(platformDensity, appearance.interfaceScale, applyInterfaceScale) {
            if (applyInterfaceScale) {
                Density(
                    density = platformDensity.density * appearance.interfaceScale,
                    fontScale = platformDensity.fontScale,
                )
            } else {
                platformDensity
            }
        }

        // COUI 风格下全局文字改用 COUI-Kit 的字号/字重（列表标题 16 中粗、说明 13、分组标题 12、顶栏 18 等），
        // 页面里所有 MiuixTheme.textStyles.* 一次生效；Miuix 风格保持原样。
        val baseTextStyles = MiuixTheme.textStyles
        val couiItemTitle = rememberCouiTextStyle(CouiTextKind.ItemTitle)
        val couiItemSummary = rememberCouiTextStyle(CouiTextKind.ItemSummary)
        val couiItemValue = rememberCouiTextStyle(CouiTextKind.ItemValue)
        val couiGroupTitle = rememberCouiTextStyle(CouiTextKind.GroupTitle)
        val couiGroupFooter = rememberCouiTextStyle(CouiTextKind.GroupFooter)
        val couiTopBar = rememberCouiTextStyle(CouiTextKind.TopBar)
        val couiSearch = rememberCouiTextStyle(CouiTextKind.Search)
        val themedTextStyles = if (appearance.uiStyle == AppearanceUiStyle.COUI) {
            remember(baseTextStyles, couiItemTitle, couiItemSummary, couiItemValue, couiGroupTitle, couiGroupFooter, couiTopBar, couiSearch) {
                fun pick(base: androidx.compose.ui.text.TextStyle, coui: androidx.compose.ui.text.TextStyle) =
                    base.copy(fontFamily = coui.fontFamily, fontWeight = coui.fontWeight, fontSize = coui.fontSize)
                baseTextStyles.copy(
                    main = pick(baseTextStyles.main, couiSearch.copy(fontSize = couiItemTitle.fontSize)),
                    body1 = pick(baseTextStyles.body1, couiItemTitle.copy(fontWeight = couiSearch.fontWeight)),
                    body2 = pick(baseTextStyles.body2, couiItemValue),
                    headline1 = pick(baseTextStyles.headline1, couiItemTitle),
                    headline2 = pick(baseTextStyles.headline2, couiItemTitle),
                    subtitle = pick(baseTextStyles.subtitle, couiGroupTitle),
                    footnote1 = pick(baseTextStyles.footnote1, couiItemSummary),
                    footnote2 = pick(baseTextStyles.footnote2, couiGroupFooter),
                    title4 = pick(baseTextStyles.title4, couiTopBar),
                )
            }
        } else {
            baseTextStyles
        }
        MiuixTheme(colors = themedMiuixColors, textStyles = themedTextStyles) {
            CompositionLocalProvider(
                LocalBlurEnabled provides appearance.blurEnabled,
                LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
                LocalPlatformDensity provides platformDensity,
                LocalDensity provides appDensity,
                LocalContentColor provides themedMiuixColors.onBackground,
                LocalAppearanceSettings provides appearance,
                LocalUiStyle provides appearance.uiStyle,
                LocalCouiDarkTheme provides isDark,
                LocalCouiContourGlowEnabled provides (blurSupported && !systemOverlay),
            ) {
                if (appearance.uiStyle == AppearanceUiStyle.COUI) {
                    CouiTheme(darkTheme = isDark, colorScheme = couiNative) {
                        if (blurSupported && !systemOverlay && provideGlassHost) {
                            ProvideCouiMenuBackdrop(isolateContent = false) { windowSource ->
                                Box(Modifier.fillMaxSize().then(windowSource)) {
                                    scopedContent()
                                }
                            }
                        } else {
                            scopedContent()
                        }
                    }
                } else if (provideGlassHost) {
                    val surfaceColor = themedMiuixColors.surface
                    val delegateBackdrop = rememberGlassBackdrop(surfaceColor)
                    val sceneBackdrop = remember(delegateBackdrop, surfaceColor) {
                        delegateBackdrop?.let { DemandSceneBackdrop(it, fallbackColor = surfaceColor) }
                    }
                    val topLayer = remember { EtaTopLayerState() }
                    var hasActiveMiuixPopup by remember { mutableStateOf(false) }
                    var hasActiveTopLayerOverlay by remember { mutableStateOf(false) }
                    val hasActiveOverlay = hasActiveMiuixPopup || hasActiveTopLayerOverlay
                    val captureActive = hasActiveOverlay || sceneBackdrop?.isActive == true
                    if (sceneBackdrop != null) {
                        SideEffect {
                            sceneBackdrop.setDelegateAttached(captureActive)
                        }
                        LaunchedEffect(sceneBackdrop.isActive, hasActiveOverlay) {
                            if (!sceneBackdrop.isActive || hasActiveOverlay) return@LaunchedEffect
                            while (sceneBackdrop.isActive && !hasActiveOverlay) {
                                delay(250L)
                                sceneBackdrop.refreshIdleState()
                            }
                        }
                    }
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        contentWindowInsets = WindowInsets(0),
                        popupHost = {
                            Box(
                                modifier = Modifier.onSizeChanged { size ->
                                    hasActiveMiuixPopup = size.width > 0 && size.height > 0
                                },
                            ) {
                                MiuixPopupHost()
                            }
                        },
                    ) {
                        CompositionLocalProvider(
                            LocalGlassSceneBackdrop provides sceneBackdrop,
                            LocalEtaTopLayer provides topLayer,
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .captureGlassBackdrop(
                                            if (captureActive) delegateBackdrop else null,
                                        ),
                                ) {
                                    scopedContent()
                                }
                                EtaTopLayerHost(
                                    state = topLayer,
                                    onActiveOverlayChanged = { hasActiveTopLayerOverlay = it },
                                )
                            }
                        }
                    }
                } else {
                    scopedContent()
                }
            }
        }
    }
}

private fun AppearancePaletteStyle.toMiuixPaletteStyle(): ThemePaletteStyle = when (this) {
    AppearancePaletteStyle.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    AppearancePaletteStyle.NEUTRAL -> ThemePaletteStyle.Neutral
    AppearancePaletteStyle.VIBRANT -> ThemePaletteStyle.Vibrant
    AppearancePaletteStyle.EXPRESSIVE -> ThemePaletteStyle.Expressive
    AppearancePaletteStyle.RAINBOW -> ThemePaletteStyle.Rainbow
    AppearancePaletteStyle.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    AppearancePaletteStyle.MONOCHROME -> ThemePaletteStyle.Monochrome
    AppearancePaletteStyle.FIDELITY -> ThemePaletteStyle.Fidelity
    AppearancePaletteStyle.CONTENT -> ThemePaletteStyle.Content
    AppearancePaletteStyle.CMF -> ThemePaletteStyle.Cmf
}

private fun AppearanceAccentColor.seedColor(): Color = when (this) {
    AppearanceAccentColor.SYSTEM, AppearanceAccentColor.BLUE -> Color(0xFF3482FF)
    AppearanceAccentColor.PURPLE -> Color(0xFF6750A4)
    AppearanceAccentColor.PINK -> Color(0xFFB0006D)
    AppearanceAccentColor.RED -> Color(0xFFBA1A1A)
    AppearanceAccentColor.ORANGE -> Color(0xFFB65D00)
    AppearanceAccentColor.YELLOW -> Color(0xFF7D5700)
    AppearanceAccentColor.GREEN -> Color(0xFF006D3B)
    AppearanceAccentColor.TEAL -> Color(0xFF006A6A)
}

@Composable
private fun miuixBackedMaterialColors(isDark: Boolean): androidx.compose.material3.ColorScheme {
    val c = MiuixTheme.colorScheme
    return remember(c, isDark) {
        if (isDark) {
            materialDarkColorScheme(
                primary = c.primary, onPrimary = c.onPrimary,
                primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
                secondary = c.secondary, onSecondary = c.onSecondary,
                secondaryContainer = c.secondaryContainer, onSecondaryContainer = c.onSecondaryContainer,
                background = c.background, onBackground = c.onBackground,
                surface = c.surface, onSurface = c.onSurface,
                surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.onSurfaceSecondary,
                error = c.error, onError = c.onError,
                errorContainer = c.errorContainer, onErrorContainer = c.onErrorContainer,
                outline = c.outline, outlineVariant = c.outline, surfaceTint = c.primary,
                surfaceContainer = c.surfaceContainer, surfaceContainerHigh = c.surfaceContainerHigh,
                surfaceContainerHighest = c.surfaceContainerHigh,
                surfaceContainerLow = c.surface, surfaceContainerLowest = c.surface,
            )
        } else {
            materialLightColorScheme(
                primary = c.primary, onPrimary = c.onPrimary,
                primaryContainer = c.primaryContainer, onPrimaryContainer = c.onPrimaryContainer,
                secondary = c.secondary, onSecondary = c.onSecondary,
                secondaryContainer = c.secondaryContainer, onSecondaryContainer = c.onSecondaryContainer,
                background = c.background, onBackground = c.onBackground,
                surface = c.surface, onSurface = c.onSurface,
                surfaceVariant = c.surfaceVariant, onSurfaceVariant = c.onSurfaceSecondary,
                error = c.error, onError = c.onError,
                errorContainer = c.errorContainer, onErrorContainer = c.onErrorContainer,
                outline = c.outline, outlineVariant = c.outline, surfaceTint = c.primary,
                surfaceContainer = c.surfaceContainer, surfaceContainerHigh = c.surfaceContainerHigh,
                surfaceContainerHighest = c.surfaceContainerHigh,
                surfaceContainerLow = c.surface, surfaceContainerLowest = c.surface,
            )
        }
    }
}
