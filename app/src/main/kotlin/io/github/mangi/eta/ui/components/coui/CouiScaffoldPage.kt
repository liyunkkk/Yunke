package io.github.mangi.eta.ui.components.coui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.MutableWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageColors
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.scroll.CouiOverscrollEffect
import com.itos.coui.scroll.couiVerticalScroll
import com.itos.coui.scroll.rememberCouiOverscrollEffect
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.rememberCouiTextStyle
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiContentColor
import com.itos.coui.theme.LocalCouiDarkTheme
import com.itos.coui.theme.LocalCouiTextStyle
import com.itos.coui.topappbar.CouiCollapsingAppBarDefaults
import com.itos.coui.topappbar.CouiTopAppBarButtonDefaults
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import kotlin.math.roundToInt

/** 标记当前处于 COUI 顶栏作用域内，供 AdaptiveControls 渲染顶栏胶囊/圆形按钮。 */
internal val LocalInCouiTopAppBar = compositionLocalOf { false }

private val FastTopBarTitleEasing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private val FastTopBarHorizontalPadding = 12.dp
private val FastTopBarTitleGap = 8.dp
private val FastTopBarFadeExtension = 10.dp

private enum class FastScaffoldSlot {
    TopBar,
    BottomBar,
    Content,
    FloatingAction,
}

@Stable
private class FastScaffoldPadding : PaddingValues {
    var value by mutableStateOf(PaddingValues(0.dp))
    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = value.calculateLeftPadding(layoutDirection)
    override fun calculateTopPadding() = value.calculateTopPadding()
    override fun calculateRightPadding(layoutDirection: LayoutDirection) = value.calculateRightPadding(layoutDirection)
    override fun calculateBottomPadding() = value.calculateBottomPadding()
}

/**
 * 轻量 COUI 大标题折叠状态：纯 Compose 偏移与弹簧吸附，不依赖离屏 Backdrop 或 ViewOffsetHelper。
 */
@Stable
internal class CouiFastCollapsingState(
    initialRangePx: Float,
    internal var canScroll: () -> Boolean,
) {
    internal var rangePx by mutableFloatStateOf(initialRangePx.coerceAtLeast(1f))
    internal var offsetPx by mutableFloatStateOf(0f)

    val collapsedFraction: Float
        get() = (offsetPx / rangePx).coerceIn(0f, 1f)

    internal fun updateRange(newRangePx: Float) {
        val clamped = newRangePx.coerceAtLeast(1f)
        if (clamped == rangePx) return
        val fraction = collapsedFraction
        rangePx = clamped
        offsetPx = (fraction * clamped).coerceIn(0f, clamped)
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y >= 0f || !canScroll()) return Offset.Zero
            val previous = offsetPx
            val next = (previous - available.y).coerceIn(0f, rangePx)
            val consumed = previous - next
            if (consumed != 0f) offsetPx = next
            return Offset(0f, consumed)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (available.y <= 0f) return Offset.Zero
            val previous = offsetPx
            val next = (previous - available.y).coerceIn(0f, rangePx)
            val delta = previous - next
            if (delta != 0f) offsetPx = next
            return Offset(0f, delta)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (offsetPx > 0f && offsetPx < rangePx) {
                val target = if (offsetPx < rangePx * 0.5f) 0f else rangePx
                animate(
                    initialValue = offsetPx,
                    targetValue = target,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) { value, _ ->
                    offsetPx = value.coerceIn(0f, rangePx)
                }
            }
            return Velocity.Zero
        }
    }
}

@Composable
internal fun rememberCouiFastCollapsingState(
    canScroll: () -> Boolean = { true },
): CouiFastCollapsingState {
    val density = LocalDensity.current
    val rangePx = with(density) {
        (CouiTopAppBarDefaults.expandedHeight.roundToPx() - CouiTopAppBarDefaults.collapsedHeight.roundToPx())
            .coerceAtLeast(1)
            .toFloat()
    }
    val state = remember { CouiFastCollapsingState(rangePx, canScroll) }
    SideEffect {
        state.canScroll = canScroll
        state.updateRange(rangePx)
    }
    return state
}

/**
 * 零离屏 FBO 的 COUI 页面容器：保留完整安全区、顶栏/底栏与 FAB 布局语义，
 * 但不为正文挂载全屏 `couiBackdrop`（CompositingStrategy.Offscreen），彻底消除滚动/转场/抽屉动画掉帧。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CouiFastScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    containerColor: Color = CouiTheme.colorScheme.background,
    windowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    floatingActionButton: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val remainingInsets = remember(windowInsets) { MutableWindowInsets(windowInsets) }
    val contentPadding = remember { FastScaffoldPadding() }
    val body: @Composable () -> Unit = remember(content, containerColor) {
        {
            Box(Modifier.fillMaxSize().background(containerColor)) {
                content(contentPadding)
            }
        }
    }
    SubcomposeLayout(
        modifier = modifier
            .onConsumedWindowInsetsChanged { remainingInsets.insets = windowInsets.exclude(it) }
            .drawBehind {
                val left = remainingInsets.getLeft(this, layoutDirection).toFloat().coerceAtMost(size.width)
                val right = remainingInsets.getRight(this, layoutDirection).toFloat().coerceAtMost(size.width - left)
                if (left > 0f) drawRect(containerColor, size = Size(left, size.height))
                if (right > 0f) {
                    drawRect(containerColor, topLeft = Offset(size.width - right, 0f), size = Size(right, size.height))
                }
            }
            .windowInsetsPadding(windowInsets.only(WindowInsetsSides.Horizontal)),
    ) { constraints ->
        require(constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            "CouiFastScaffold 需要有界的宽度和高度"
        }
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val top = subcompose(FastScaffoldSlot.TopBar, topBar).map { it.measure(looseConstraints) }
        val bottom = subcompose(FastScaffoldSlot.BottomBar, bottomBar).map { it.measure(looseConstraints) }
        val topHeight = top.maxOfOrNull { it.height } ?: 0
        val bottomHeight = bottom.maxOfOrNull { it.height } ?: 0
        contentPadding.value = PaddingValues(
            top = (if (topHeight > 0) topHeight else remainingInsets.getTop(this)).toDp(),
            bottom = (if (bottomHeight > 0) bottomHeight else remainingInsets.getBottom(this)).toDp(),
        )
        val bodyPlaceables = subcompose(FastScaffoldSlot.Content, body).map { it.measure(looseConstraints) }
        val floating = subcompose(FastScaffoldSlot.FloatingAction) {
            if (floatingActionButton != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .consumeWindowInsets(contentPadding),
                    content = floatingActionButton,
                )
            }
        }.map { it.measure(looseConstraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            bodyPlaceables.forEach { it.placeRelative(0, 0) }
            top.forEach { it.placeRelative(0, 0) }
            bottom.forEach { it.placeRelative(0, constraints.maxHeight - bottomHeight) }
            floating.forEach { it.placeRelative(0, 0) }
        }
    }
}

/**
 * 轻量 COUI 紧凑顶栏：纯 HWUI 渐隐底色（无 RuntimeShader 渐进模糊），保持 ColorOS 17 视觉比例与字号。
 */
@Composable
internal fun CouiFastTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    dark: Boolean = CouiTheme.isDark,
    titleColor: Color = CouiPageDefaults.themeColors(dark).title,
    backgroundColor: Color = CouiPageDefaults.themeColors(dark).background,
    windowInsets: WindowInsets = CouiTopAppBarDefaults.windowInsets,
    modifier: Modifier = Modifier,
) {
    CouiFastTopAppBarLayout(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        dark = dark,
        titleColor = titleColor,
        backgroundColor = backgroundColor,
        windowInsets = windowInsets,
        expandedHeight = CouiTopAppBarDefaults.height,
        collapsedHeight = CouiTopAppBarDefaults.height,
        collapsingState = null,
        modifier = modifier,
    )
}

/**
 * 轻量 COUI 大标题弧线折叠顶栏：纯 GPU 缩放与弧线轨迹 + 纯 HWUI 渐隐底色，滚动过程零重组、零离屏模糊。
 */
@Composable
internal fun CouiFastLargeTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    collapsingState: CouiFastCollapsingState? = null,
    dark: Boolean = CouiTheme.isDark,
    titleColor: Color = CouiPageDefaults.themeColors(dark).title,
    backgroundColor: Color = CouiPageDefaults.themeColors(dark).background,
    windowInsets: WindowInsets = CouiTopAppBarDefaults.windowInsets,
    modifier: Modifier = Modifier,
) {
    CouiFastTopAppBarLayout(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        dark = dark,
        titleColor = titleColor,
        backgroundColor = backgroundColor,
        windowInsets = windowInsets,
        expandedHeight = CouiTopAppBarDefaults.expandedHeight,
        collapsedHeight = CouiTopAppBarDefaults.collapsedHeight,
        collapsingState = collapsingState,
        modifier = modifier,
    )
}

@Composable
private fun CouiFastTopAppBarLayout(
    title: String,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    dark: Boolean,
    titleColor: Color,
    backgroundColor: Color,
    windowInsets: WindowInsets,
    expandedHeight: Dp,
    collapsedHeight: Dp,
    collapsingState: CouiFastCollapsingState?,
    modifier: Modifier = Modifier,
) {
    val large = expandedHeight > collapsedHeight
    val density = LocalDensity.current
    val compactTitleStyle = CouiTopAppBarDefaults.titleTextStyle().copy(color = titleColor)
    val expandedTitleStyle = rememberCouiTextStyle(CouiTextKind.HomeTitle).copy(color = titleColor)
    val activeTitleStyle = if (large) expandedTitleStyle else compactTitleStyle
    val solidBg = backgroundColor.copy(alpha = 1f)
    val scrimStops = remember(solidBg) {
        arrayOf(
            0f to solidBg,
            0.78f to solidBg.copy(alpha = 0.94f),
            1f to solidBg.copy(alpha = 0f),
        )
    }

    CompositionLocalProvider(LocalCouiDarkTheme provides dark) {
        Layout(
            modifier = modifier
                .fillMaxWidth()
                .drawBehind {
                    val totalHeight = size.height + FastTopBarFadeExtension.toPx()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = scrimStops,
                            startY = 0f,
                            endY = totalHeight,
                        ),
                        size = Size(size.width, totalHeight),
                    )
                }
                .windowInsetsPadding(windowInsets),
            content = {
                Box {
                    CompositionLocalProvider(
                        LocalInCouiTopAppBar provides true,
                        LocalCouiContentColor provides titleColor,
                    ) {
                        navigationIcon()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(
                        LocalInCouiTopAppBar provides true,
                        LocalCouiContentColor provides titleColor,
                    ) {
                        actions()
                    }
                }
                Box(Modifier.semantics { heading() }) {
                    CompositionLocalProvider(
                        LocalCouiContentColor provides titleColor,
                        LocalCouiTextStyle provides activeTitleStyle,
                    ) {
                        BasicText(
                            text = title,
                            style = activeTitleStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        ) { nodes, constraints ->
            val width = constraints.maxWidth
            val expanded = expandedHeight.roundToPx()
            val collapsed = collapsedHeight.roundToPx()
            val range = (expanded - collapsed).coerceAtLeast(0)
            val offset = (collapsingState?.offsetPx ?: 0f).coerceIn(0f, range.toFloat())
            val height = constraints.constrainHeight((expanded - offset).roundToInt())
            val toolbarHeight = if (large) minOf(collapsed, height) else height
            val padding = FastTopBarHorizontalPadding.roundToPx().coerceAtMost(width / 2)
            val gap = FastTopBarTitleGap.roundToPx()
            val slotWidth = (width - 2 * padding).coerceAtLeast(0)
            val navigation = nodes[0].measure(Constraints(maxWidth = slotWidth, maxHeight = toolbarHeight))
            val action = nodes[1].measure(Constraints(maxWidth = (slotWidth - navigation.width).coerceAtLeast(0), maxHeight = toolbarHeight))
            val hasNavigationIcon = navigation.width > 0
            val rawFraction = if (large && range > 0) (offset / range.toFloat()).coerceIn(0f, 1f) else 0f
            val progress = if (large) FastTopBarTitleEasing.transform(rawFraction) else 0f
            val scale = if (large) {
                val collapsedScale = with(density) {
                    (compactTitleStyle.fontSize.toPx() / expandedTitleStyle.fontSize.toPx()).coerceIn(0.5f, 1f)
                }
                1f + (collapsedScale - 1f) * progress
            } else {
                1f
            }
            val compactStart = if (large && !hasNavigationIcon) {
                CouiCollapsingAppBarDefaults.titleOnlyStart.toPx()
            } else {
                (padding + navigation.width + if (hasNavigationIcon) gap else 0).toFloat()
            }
            val compactEnd = padding + action.width + if (action.width > 0) gap else 0
            val expandedStart = CouiCollapsingAppBarDefaults.titleStart.toPx()
            val curvedTitle = large && hasNavigationIcon
            val arcEnvelope = progress * (1f - progress)
            val arcOffset = if (curvedTitle) 0.8f * arcEnvelope * arcEnvelope else 0f
            val horizontalProgress = progress + arcOffset
            val start = if (large) {
                expandedStart + (compactStart - expandedStart) * horizontalProgress
            } else {
                compactStart
            }
            val end = if (large && action.width == 0) {
                CouiCollapsingAppBarDefaults.titleOnlyStart.toPx()
            } else {
                compactEnd.toFloat()
            }
            val titleMaxWidth = ((width - start - end).coerceAtLeast(0f) / scale).roundToInt()
            val titleMaxHeight = (height / scale).roundToInt().coerceAtLeast(1)
            val titleNode = nodes[2].measure(Constraints(maxWidth = titleMaxWidth, maxHeight = titleMaxHeight))
            val titleOrigin = TransformOrigin(if (layoutDirection == LayoutDirection.Rtl) 1f else 0f, 0f)
            layout(width, height) {
                navigation.placeRelative(padding, (toolbarHeight - navigation.height) / 2, zIndex = 1f)
                action.placeRelative(width - padding - action.width, (toolbarHeight - action.height) / 2, zIndex = 1f)
                val titleY = if (large) {
                    val expandedY = expanded - (CouiTopAppBarDefaults.expandedHeight - CouiCollapsingAppBarDefaults.titleOnlyTop).toPx()
                    val collapsedY = (collapsed - titleNode.height * scale) / 2f
                    if (curvedTitle) {
                        val verticalProgress = progress - arcOffset
                        expandedY + (collapsedY - expandedY) * verticalProgress
                    } else {
                        expandedY + (range + collapsedY - expandedY) * progress - offset
                    }
                } else {
                    (height - titleNode.height) / 2f
                }
                titleNode.placeRelativeWithLayer(
                    x = start.roundToInt(),
                    y = (if (curvedTitle) {
                        titleY.coerceAtLeast(0f)
                    } else {
                        titleY.coerceIn(0f, (height - titleNode.height * scale).coerceAtLeast(0f))
                    }).roundToInt(),
                ) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = titleOrigin
                    clip = true
                }
            }
        }
    }
}

@Composable
internal fun CouiBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    backDescription: String = stringResource(R.string.action_back),
) {
    CouiTopAppBarIconButton(
        icon = CouiIcons.Outlined.Back,
        contentDescription = backDescription,
        onClick = onClick,
        modifier = modifier,
        dark = dark,
        colors = colors,
    )
}

@Composable
internal fun CouiTopAppBarSlotButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    // 统一采用纯 HWUI 圆形悬浮按钮 + COUI 按压回弹，避免 API 34+ 每次切页同步编译 3 个 AGSL RuntimeShader 导致掉帧。
    val source = remember { MutableInteractionSource() }
    val circleShape = androidx.compose.foundation.shape.CircleShape
    val surfaceColor = if (dark) Color(0xFF2A2B2F) else Color.White
    val borderColor = if (dark) Color(0x24FFFFFF) else Color(0x14000000)
    val shadowTint = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f)
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(
                    elevation = if (dark) 3.dp else 2.dp,
                    shape = circleShape,
                    clip = false,
                    ambientColor = shadowTint,
                    spotColor = shadowTint,
                )
                .clip(circleShape)
                .background(surfaceColor, circleShape)
                .border(0.5.dp, borderColor, circleShape)
                .couiButtonFeedback(dark, source, enabled),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
internal fun CouiTopAppBarIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    iconSize: Dp = 24.dp,
    tint: Color = Color.Unspecified,
) {
    CouiTopAppBarSlotButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        dark = dark,
        colors = colors,
        contentDescription = contentDescription,
    ) {
        val resolvedTint = tint.takeOrElse {
            if (enabled) {
                LocalCouiContentColor.current.takeOrElse { colors.title }
            } else {
                colors.disabled
            }
        }
        CouiIcon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = resolvedTint,
        )
    }
}

@Composable
internal fun CouiTopAppBarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
) {
    val source = remember { MutableInteractionSource() }
    val pillShape = androidx.compose.foundation.shape.CircleShape
    val textStyle = rememberCouiTextStyle(CouiTextKind.ItemValue)
    val textColor = if (enabled) colors.title else colors.disabled
    val surfaceColor = if (dark) Color(0xFF2A2B2F) else Color.White
    val borderColor = if (dark) Color(0x24FFFFFF) else Color(0x14000000)
    val shadowTint = Color.Black.copy(alpha = if (dark) 0.30f else 0.10f)
    Box(
        modifier = modifier
            .widthIn(min = 60.dp)
            .heightIn(min = 40.dp)
            .shadow(
                elevation = if (dark) 3.dp else 2.dp,
                shape = pillShape,
                clip = false,
                ambientColor = shadowTint,
                spotColor = shadowTint,
            )
            .clip(pillShape)
            .background(surfaceColor, pillShape)
            .border(0.5.dp, borderColor, pillShape)
            .couiButtonFeedback(dark, source, enabled)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = textStyle.copy(color = textColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * COUI 二级页骨架（ScrollState + Column）：
 * 手机使用大标题弧线折叠顶栏，宽屏或 compactTitle 使用紧凑顶栏；
 * 仅在宿主窗口持有焦点时应用 imePadding，避免输入弹窗打开时背景页面提前空出键盘高度。
 */
@Composable
internal fun CouiScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    scrollState: ScrollState = rememberScrollState(),
    compactTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    overscrollEffect: CouiOverscrollEffect? = rememberCouiOverscrollEffect(),
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val useCompact = compactTitle || rememberIsWideScreen()
    val collapsingState = if (useCompact) {
        null
    } else {
        rememberCouiFastCollapsingState(canScroll = { scrollState.maxValue > 0 })
    }
    val imePadding = if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier
    val navigation: @Composable () -> Unit = {
        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
    }
    CouiFastScaffold(
        modifier = modifier
            .fillMaxSize()
            .then(imePadding)
            .then(if (collapsingState != null) Modifier.nestedScroll(collapsingState.nestedScrollConnection) else Modifier),
        containerColor = colors.background,
        floatingActionButton = floatingAction,
        topBar = {
            if (useCompact) {
                CouiFastTopAppBar(
                    title = title,
                    navigationIcon = navigation,
                    actions = actions,
                    dark = dark,
                    titleColor = colors.title,
                    backgroundColor = colors.background,
                )
            } else {
                CouiFastLargeTopAppBar(
                    title = title,
                    navigationIcon = navigation,
                    actions = actions,
                    collapsingState = collapsingState,
                    dark = dark,
                    titleColor = colors.title,
                    backgroundColor = colors.background,
                )
            }
        },
    ) { innerPadding ->
        WidePageContent { sidePadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .couiVerticalScroll(scrollState, overscrollEffect = overscrollEffect)
                    .padding(innerPadding)
                    .padding(horizontal = sidePadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(bottom = CouiPageDefaults.groupSpacing),
                content = content,
            )
        }
        overlay()
    }
}

/**
 * COUI 二级页骨架（LazyColumn），用于长动态列表页。
 */
@Composable
internal fun CouiLazyScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    listState: LazyListState = rememberLazyListState(),
    compactTitle: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    overlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val useCompact = compactTitle || rememberIsWideScreen()
    val collapsingState = if (useCompact) {
        null
    } else {
        rememberCouiFastCollapsingState(
            canScroll = { listState.canScrollForward || listState.canScrollBackward },
        )
    }
    val overscroll = rememberCouiOverscrollEffect()
    val imePadding = if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier
    val navigation: @Composable () -> Unit = {
        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
    }
    CouiFastScaffold(
        modifier = modifier
            .fillMaxSize()
            .then(imePadding)
            .then(if (collapsingState != null) Modifier.nestedScroll(collapsingState.nestedScrollConnection) else Modifier),
        containerColor = colors.background,
        floatingActionButton = floatingAction,
        topBar = {
            if (useCompact) {
                CouiFastTopAppBar(
                    title = title,
                    navigationIcon = navigation,
                    actions = actions,
                    dark = dark,
                    titleColor = colors.title,
                    backgroundColor = colors.background,
                )
            } else {
                CouiFastLargeTopAppBar(
                    title = title,
                    navigationIcon = navigation,
                    actions = actions,
                    collapsingState = collapsingState,
                    dark = dark,
                    titleColor = colors.title,
                    backgroundColor = colors.background,
                )
            }
        },
    ) { innerPadding ->
        WidePageContent { sidePadding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    top = innerPadding.calculateTopPadding(),
                    end = sidePadding,
                    bottom = innerPadding.calculateBottomPadding() + CouiPageDefaults.groupSpacing,
                ),
                overscrollEffect = overscroll,
            ) {
                content()
            }
        }
        overlay()
    }
}

/**
 * 自定义容器（如分页、整页编辑器）使用的低层 COUI 二级页骨架。
 */
@Composable
internal fun CouiScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = CouiTheme.isDark,
    colors: CouiPageColors = CouiPageDefaults.themeColors(dark),
    compactTitle: Boolean = false,
    canScroll: () -> Boolean = { true },
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable (
        contentPadding: PaddingValues,
        rawPadding: PaddingValues,
        nestedScrollConnection: NestedScrollConnection?,
        sidePadding: Dp,
    ) -> Unit,
) {
    val useCompact = compactTitle || rememberIsWideScreen()
    val collapsingState = if (useCompact) {
        null
    } else {
        rememberCouiFastCollapsingState(canScroll = canScroll)
    }
    val navigation: @Composable () -> Unit = {
        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
    }
    CouiFastScaffold(
        modifier = modifier
            .fillMaxSize()
            .then(if (collapsingState != null) Modifier.nestedScroll(collapsingState.nestedScrollConnection) else Modifier),
        containerColor = colors.background,
        floatingActionButton = floatingAction,
        topBar = {
            if (useCompact) {
                CouiFastTopAppBar(
                    title = title,
                    navigationIcon = navigation,
                    actions = actions,
                    dark = dark,
                    titleColor = colors.title,
                    backgroundColor = colors.background,
                )
            } else {
                CouiFastLargeTopAppBar(
                    title = title,
                    navigationIcon = navigation,
                    actions = actions,
                    collapsingState = collapsingState,
                    dark = dark,
                    titleColor = colors.title,
                    backgroundColor = colors.background,
                )
            }
        },
    ) { innerPadding ->
        WidePageContent { sidePadding ->
            content(innerPadding, innerPadding, collapsingState?.nestedScrollConnection, sidePadding)
        }
    }
}

@Composable
internal fun CouiPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
