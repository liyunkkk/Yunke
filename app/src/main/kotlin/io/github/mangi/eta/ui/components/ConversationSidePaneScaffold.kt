package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import com.itos.coui.icons.CouiIcons
import com.itos.coui.theme.CouiTheme
import androidx.compose.material3.HorizontalDivider
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.searchResults
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.DpOffset
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val DrawerMenuItemModifier = Modifier.height(40.dp)
private val DrawerMenuItemPadding = PaddingValues(horizontal = 12.dp)

private object DrawerMetrics {
    val PaneMaxWidth = 320.dp
    val PaneWidthFraction = 0.78f
    val DrawerCornerRadius = 28.dp
    const val OpenDurationMs = 220
    const val CloseDurationMs = 200
    val PaneHorizontalPadding = 16.dp
    val TopInset = 8.dp
    val AfterSearch = 12.dp
    val SearchIconSize = 18.dp
    val SearchCornerRadius = 14.dp
    val SearchVerticalPadding = 10.dp
    val ChipGap = 8.dp
    val AfterChips = 10.dp
    val ListBottomPadding = 12.dp
    val BottomInset = 10.dp
    val ActionIconSize = 20.dp
    val CircleButtonPadding = 10.dp
    val SectionTopPadding = 10.dp
    val SectionBottomPadding = 6.dp
    val RowMinHeight = 44.dp
    val RowGap = 2.dp
    val RowCornerRadius = 18.dp
    val RowHorizontalPadding = 14.dp
    val RowVerticalPadding = 10.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 8.dp
    val EmptyVerticalPadding = 28.dp
    val DockTopGap = 8.dp
    val AssistantBarHeight = 48.dp
}

@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExportMarkdown: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenScheduledTasks: () -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val drawerState = rememberDrawerState(
        initialValue = if (visible) DrawerValue.Open else DrawerValue.Closed,
    )
    remember(drawerState) {
        drawerState.applyEtaDrawerMotion()
        true
    }
    val drawerScope = rememberCoroutineScope()
    val currentVisible by rememberUpdatedState(visible)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var drawerBlocksIme by remember { mutableStateOf(visible) }

    fun launchAfterDrawerClosed(action: () -> Unit) {
        drawerScope.launch {
            if (drawerState.targetValue != DrawerValue.Closed ||
                drawerState.currentValue != DrawerValue.Closed
            ) {
                drawerState.snapTo(DrawerValue.Closed)
            }
            action()
        }
    }

    LaunchedEffect(visible) {
        val target = if (visible) DrawerValue.Open else DrawerValue.Closed
        if (drawerState.targetValue == target) return@LaunchedEffect
        if (!visible && drawerState.currentValue == DrawerValue.Open) {
            // 从底部按钮切页：抽屉是完全打开的，瞬时合上再让新页入场。
            // 手势收回时 currentValue 会先变成 Closed，这里不会走到。
            drawerState.snapTo(target)
            return@LaunchedEffect
        }
        @Suppress("DEPRECATION")
        drawerState.animateTo(
            targetValue = target,
            anim = if (visible) DrawerOpenMotion else DrawerCloseMotion,
        )
    }

    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue to drawerState.isAnimationRunning }
            .collectLatest { (value, running) ->
                // 快滑时 currentValue 会在 settle 开始就切换。立刻改 visible 会让
                // 聊天页在惯性动画中间整页重组，看起来就是顿一下。
                if (running) return@collectLatest
                if (value == DrawerValue.Open) {
                    if (!currentVisible) currentOnOpen()
                } else if (currentVisible) {
                    currentOnDismiss()
                }
            }
    }

    LaunchedEffect(visible) {
        drawerBlocksIme = visible
    }

    LaunchedEffect(drawerState, visible, focusManager, keyboard, view) {
        snapshotFlow {
            visible ||
                drawerState.targetValue == DrawerValue.Open ||
                drawerState.currentValue == DrawerValue.Open
        }.collect { shouldHide ->
            if (shouldHide) hideChatInputIme(focusManager, keyboard, view)
        }
    }

    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = visible &&
            backHandlerEnabled &&
            sceneLifecycleState == Lifecycle.State.RESUMED,
        onBackCompleted = { currentOnDismiss() },
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val paneWidth = minOf(maxWidth * DrawerMetrics.PaneWidthFraction, DrawerMetrics.PaneMaxWidth)
        val drawerShape = AbsoluteRoundedCornerShape(
            topLeft = 0.dp,
            topRight = DrawerMetrics.DrawerCornerRadius,
            bottomRight = DrawerMetrics.DrawerCornerRadius,
            bottomLeft = 0.dp,
        )
        CompositionLocalProvider(LocalConversationDrawerBlocksIme provides drawerBlocksIme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = backHandlerEnabled,
            scrimColor = MiuixTheme.colorScheme.windowDimming,
            modifier = Modifier.fillMaxSize(),
            drawerContent = {
                ConversationPanePanel(
                    state = state,
                    width = paneWidth,
                    drawerShape = drawerShape,
                    // Only the search row observes open/settle state. The panel and its
                    // lazy item provider must stay reusable when the drawer opens.
                    enableSearchFocus = {
                        currentVisible && drawerState.currentValue == DrawerValue.Open &&
                            !drawerState.isAnimationRunning
                    },
                    onSearchChange = onSearchChange,
                    onConversationSelected = onConversationSelected,
                    onConversationRename = onConversationRename,
                    onConversationDelete = onConversationDelete,
                    onMoveConversationToFolder = onMoveConversationToFolder,
                    onNewConversation = onNewConversation,
                    onConversationTogglePin = onConversationTogglePin,
                    onConversationExportMarkdown = onConversationExportMarkdown,
                    onConversationExport = onConversationExport,
                    onOpenManageChats = { launchAfterDrawerClosed(onOpenManageChats) },
                    onSelectFolder = onSelectFolder,
                    onCreateFolder = onCreateFolder,
                    onRenameFolder = onRenameFolder,
                    onDeleteFolder = onDeleteFolder,
                    selectedAssistantId = selectedAssistantId,
                    onSelectAssistant = onSelectAssistant,
                    onEditAssistant = { id -> launchAfterDrawerClosed { onEditAssistant(id) } },
                    onOpenAssistants = { launchAfterDrawerClosed(onOpenAssistants) },
                    onOpenSettings = { launchAfterDrawerClosed(onOpenSettings) },
                    onOpenModelProviders = { launchAfterDrawerClosed(onOpenModelProviders) },
                    onOpenUsageStats = { launchAfterDrawerClosed(onOpenUsageStats) },
                    onOpenSkills = { launchAfterDrawerClosed(onOpenSkills) },
                    onOpenPermissions = { launchAfterDrawerClosed(onOpenPermissions) },
                    onOpenScheduledTasks = { launchAfterDrawerClosed(onOpenScheduledTasks) },
                    modifier = Modifier.fillMaxHeight(),
                )
            },
        ) {
            content()
        }
        }
    }
}

@Composable
private fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    drawerShape: AbsoluteRoundedCornerShape,
    enableSearchFocus: () -> Boolean,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExportMarkdown: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenScheduledTasks: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val query = state.searchQuery.trim()
    val visibleConversations = remember(state.conversations, query) {
        state.searchResults()
    }
    val groups = remember(visibleConversations) { visibleConversations.groupForDrawer() }
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId.ifBlank { repositoryActiveId }
    val activeAssistant = remember(profiles, activeId) {
        profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = if (isCoui) CouiTheme.isDark else false
    val paneBg = if (isCoui) {
        if (couiDark) Color(0xFF141519) else Color(0xFFF6F6FA)
    } else {
        MiuixTheme.colorScheme.surface
    }
    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                clip = true
                shape = drawerShape
            },
        shape = drawerShape,
        color = paneBg,
        contentColor = MiuixTheme.colorScheme.onSurface,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // 搜索框在顶部，键盘不必把底部 dock 和会话输入一起顶上来。
                .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                .consumeWindowInsets(WindowInsets.ime)
                .padding(horizontal = DrawerMetrics.PaneHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            PaneDrawerActions(
                onNewConversation = onNewConversation,
                onOpenManageChats = onOpenManageChats,
            )
            Spacer(modifier = Modifier.height(12.dp))
            PaneSearchRow(
                query = state.searchQuery,
                onSearchChange = onSearchChange,
                enableSearchFocus = enableSearchFocus,
            )
            Spacer(modifier = Modifier.height(12.dp))
            PaneFolderBar(
                folders = state.folders,
                selectedFolderId = state.selectedFolderId,
                onSelectFolder = onSelectFolder,
                onCreateFolder = onCreateFolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder,
                onOpenManageChats = onOpenManageChats,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .streamDiagnosticMeasure("drawer.lazy.measure")
                    .streamDiagnosticPlacement("drawer.lazy.place")
                    .streamDiagnosticDraw("drawer.lazy.draw")
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(bottom = DrawerMetrics.ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(DrawerMetrics.RowGap),
                overscrollEffect = null,
            ) {
                if (visibleConversations.isEmpty()) {
                    item(contentType = "drawer-empty") {
                        EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEach { group ->
                        item(key = group.key, contentType = "drawer-section") {
                            ConversationSectionHeader(group = group)
                        }
                        items(
                            items = group.items,
                            key = { it.id },
                            contentType = { "drawer-conversation" },
                        ) { conversation ->
                            ConversationTextRow(
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                onClick = { onConversationSelected(conversation.id) },
                                onRename = { onConversationRename(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                                onMoveToFolder = { onMoveConversationToFolder(conversation) },
                                onTogglePin = { onConversationTogglePin(conversation) },
                                onExportMarkdown = { onConversationExportMarkdown(conversation) },
                                onExport = { onConversationExport(conversation) },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            PaneFooterBar(
                assistantName = activeAssistant?.name?.ifBlank { stringResource(R.string.app_name) }
                    ?: stringResource(R.string.app_name),
                avatar = {
                    AssistantAvatar(
                        assistant = activeAssistant,
                        size = 36.dp,
                    )
                },
                onOpenAssistants = onOpenAssistants,
                onOpenPermissions = onOpenPermissions,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenUsageStats = onOpenUsageStats,
                onOpenSkills = onOpenSkills,
                onOpenScheduledTasks = onOpenScheduledTasks,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.BottomInset))
        }
    }

}

@Composable
private fun PaneSearchRow(
    query: String,
    onSearchChange: (String) -> Unit,
    enableSearchFocus: () -> Boolean,
) {
    val searchFocusEnabled = enableSearchFocus()
    val textStyle = MiuixTheme.textStyles.body2.merge(
        TextStyle(
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
        ),
    )
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var searchActive by remember { mutableStateOf(false) }
    LaunchedEffect(searchFocusEnabled) {
        if (!searchFocusEnabled) {
            searchActive = false
            focusManager.clearFocus(force = true)
        }
    }
    LaunchedEffect(searchActive, searchFocusEnabled) {
        if (searchActive && searchFocusEnabled) {
            showChatInputIme(searchFocusRequester, keyboard, view)
        }
    }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = if (isCoui) CouiTheme.isDark else false
    val searchBg = if (isCoui) {
        if (couiDark) Color(0xFF202227) else Color.White
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val searchBorder = if (isCoui) {
        if (couiDark) Color(0x1EFFFFFF) else Color(0x12000000)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(CircleShape)
            .background(searchBg)
            .then(if (searchBorder != Color.Transparent) Modifier.border(0.5.dp, searchBorder, CircleShape) else Modifier)
            .clickable(
                enabled = searchFocusEnabled && !searchActive,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                searchActive = true
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (isCoui) CouiIcons.Outlined.Search else Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(DrawerMetrics.SearchIconSize),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.conversation_search_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onSearchChange,
                enabled = searchFocusEnabled && searchActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester)
                    .focusProperties { canFocus = searchFocusEnabled && searchActive },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .clickable {
                        TouchHaptics.click(view)
                        onSearchChange("")
                    },
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun PaneDrawerActions(
    onNewConversation: () -> Unit,
    onOpenManageChats: () -> Unit,
) {
    val view = LocalView.current
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = if (isCoui) CouiTheme.isDark else false
    val circleBg = if (isCoui) {
        if (couiDark) Color(0xFF22242A) else Color.White
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val circleBorder = if (isCoui) {
        if (couiDark) Color(0x20FFFFFF) else Color(0x14000000)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 管理聊天圆形按钮
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(circleBg)
                .then(if (circleBorder != Color.Transparent) Modifier.border(0.5.dp, circleBorder, CircleShape) else Modifier)
                .pointerInput(onOpenManageChats) {
                    detectTapGestures(onTap = {
                        TouchHaptics.click(view)
                        onOpenManageChats()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Layers,
                contentDescription = stringResource(R.string.drawer_manage_chats),
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        // 主题色新对话胶囊主按钮
        Row(
            modifier = Modifier
                .height(38.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary)
                .pointerInput(onNewConversation) {
                    detectTapGestures(onTap = {
                        TouchHaptics.click(view)
                        onNewConversation()
                    })
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MiuixTheme.colorScheme.onPrimary,
            )
            Text(
                text = stringResource(R.string.drawer_new_conversation),
                color = MiuixTheme.colorScheme.onPrimary,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PaneFolderBar(
    folders: List<ConversationFolderUi>,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onOpenManageChats: () -> Unit = {},
) {
    val view = LocalView.current
    var createFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<ConversationFolderUi?>(null) }
    var folderToDelete by remember { mutableStateOf<ConversationFolderUi?>(null) }
    var showFolderDropdown by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val selectedFolder = remember(folders, selectedFolderId) {
        folders.firstOrNull { it.id == selectedFolderId }
    }
    val allLabel = stringResource(R.string.drawer_chats_chip)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 文件夹下拉行 + 右侧更多菜单（类似 DSH Go 的「📁 DSH worker ⌄   ···」）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            TouchHaptics.click(view)
                            showFolderDropdown = true
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = selectedFolder?.name ?: allLabel,
                        color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                EtaDropdownMenu(
                    expanded = showFolderDropdown,
                    onDismissRequest = { showFolderDropdown = false },
                    offset = DpOffset(x = 0.dp, y = 6.dp),
                ) {
                    DropdownMenuItem(
                        modifier = DrawerMenuItemModifier,
                        contentPadding = DrawerMenuItemPadding,
                        text = {
                            Text(
                                text = allLabel,
                                fontWeight = if (selectedFolderId == null) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = if (selectedFolderId == null) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            }
                        } else null,
                        onClick = {
                            showFolderDropdown = false
                            onSelectFolder(null)
                        },
                    )
                    folders.forEach { folder ->
                        val isSelected = selectedFolderId == folder.id
                        DropdownMenuItem(
                            modifier = DrawerMenuItemModifier,
                            contentPadding = DrawerMenuItemPadding,
                            text = {
                                Text(
                                    text = folder.name,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            trailingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                            onClick = {
                                showFolderDropdown = false
                                onSelectFolder(folder.id)
                            },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
                    )
                    DropdownMenuItem(
                        modifier = DrawerMenuItemModifier,
                        contentPadding = DrawerMenuItemPadding,
                        text = { Text(stringResource(R.string.drawer_new_folder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            showFolderDropdown = false
                            createFolder = true
                        },
                    )
                }
            }

            Box {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable {
                            TouchHaptics.click(view)
                            showMoreMenu = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.action_more),
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                EtaDropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    alignEnd = true,
                    offset = DpOffset(x = 0.dp, y = 6.dp),
                ) {
                    DropdownMenuItem(
                        modifier = DrawerMenuItemModifier,
                        contentPadding = DrawerMenuItemPadding,
                        text = { Text(stringResource(R.string.drawer_new_folder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                            )
                        },
                        onClick = {
                            showMoreMenu = false
                            createFolder = true
                        },
                    )
                    if (selectedFolder != null) {
                        DropdownMenuItem(
                            modifier = DrawerMenuItemModifier,
                            contentPadding = DrawerMenuItemPadding,
                            text = { Text(stringResource(R.string.action_rename)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                folderToRename = selectedFolder
                            },
                        )
                        DropdownMenuItem(
                            modifier = DrawerMenuItemModifier,
                            contentPadding = DrawerMenuItemPadding,
                            text = {
                                Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MiuixTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                                    tint = MiuixTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                folderToDelete = selectedFolder
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            modifier = DrawerMenuItemModifier,
                            contentPadding = DrawerMenuItemPadding,
                            text = { Text(stringResource(R.string.drawer_manage_chats)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                                )
                            },
                            onClick = {
                                showMoreMenu = false
                                onOpenManageChats()
                            },
                        )
                    }
                }
            }
        }

        // 存在文件夹时展示单行紧凑胶囊快切条（长按仍可重命名/删除）
        if (folders.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DrawerMetrics.ChipGap),
            ) {
                item(key = "unfiled") {
                    DrawerChip(
                        label = allLabel,
                        selected = selectedFolderId == null,
                        icon = null,
                        onClick = { onSelectFolder(null) },
                    )
                }
                items(folders, key = { it.id }) { folder ->
                    var showMenu by remember(folder.id) { mutableStateOf(false) }
                    Box {
                        DrawerChip(
                            label = folder.name,
                            selected = selectedFolderId == folder.id,
                            icon = null,
                            onClick = { onSelectFolder(folder.id) },
                            onLongClick = { showMenu = true },
                        )
                        EtaDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = DpOffset(x = 0.dp, y = 8.dp),
                        ) {
                            DropdownMenuItem(
                                modifier = DrawerMenuItemModifier,
                                contentPadding = DrawerMenuItemPadding,
                                text = { Text(stringResource(R.string.action_rename)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    folderToRename = folder
                                },
                            )
                            DropdownMenuItem(
                                modifier = DrawerMenuItemModifier,
                                contentPadding = DrawerMenuItemPadding,
                                text = {
                                    Text(
                                        text = stringResource(R.string.action_delete),
                                        color = MiuixTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                                        tint = MiuixTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    folderToDelete = folder
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (createFolder) {
        FolderNameDialog(
            title = stringResource(R.string.drawer_create_folder_title),
            initialName = "",
            onDismiss = { createFolder = false },
            onConfirm = { name ->
                onCreateFolder(name)
                createFolder = false
            },
        )
    }
    folderToRename?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.drawer_rename_folder_title),
            initialName = folder.name,
            onDismiss = { folderToRename = null },
            onConfirm = { name ->
                onRenameFolder(folder.id, name)
                folderToRename = null
            },
        )
    }
    folderToDelete?.let { folder ->
        AdaptiveConfirmDialog(
            show = true,
            title = stringResource(R.string.drawer_delete_folder_title),
            summary = stringResource(R.string.drawer_delete_folder_message),
            onDismissRequest = { folderToDelete = null },
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onCancel = { folderToDelete = null },
            onConfirm = {
                onDeleteFolder(folder.id)
                folderToDelete = null
            },
        )
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AdaptiveConfirmDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
        confirmText = stringResource(R.string.action_save),
        confirmEnabled = name.isNotBlank(),
        onCancel = onDismiss,
        onConfirm = { onConfirm(name) },
    ) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.drawer_folder_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
    }
}

@Composable
private fun DrawerChip(
    label: String,
    selected: Boolean,
    icon: ImageVector?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val background = if (selected) {
        MiuixTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MiuixTheme.colorScheme.surface
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val view = LocalView.current
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .background(background)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongPress = {
                        if (onLongClick != null) {
                            TouchHaptics.longPress(view)
                            onLongClick()
                        }
                    },
                )
            }
            .padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
        }
        Text(
            text = label,
            color = contentColor,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
) {
    Text(
        text = group.localizedLabel(),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = DrawerMetrics.RowHorizontalPadding,
                top = DrawerMetrics.SectionTopPadding,
                bottom = DrawerMetrics.SectionBottomPadding,
            ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onTogglePin: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExport: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val view = LocalView.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.RowMinHeight)
                .clip(RoundedCornerShape(DrawerMetrics.RowCornerRadius))
                .background(
                    if (selected) {
                        MiuixTheme.colorScheme.surfaceContainerHigh
                    } else {
                        Color.Transparent
                    },
                )
                .combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongClick = {
                        TouchHaptics.longPress(view)
                        showActionMenu = true
                    },
                )
                .padding(
                    horizontal = DrawerMetrics.RowHorizontalPadding,
                    vertical = DrawerMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title.ifBlank { conversation.preview },
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (conversation.isActiveRun || conversation.hasCompletionMarker) {
                Box(
                    modifier = Modifier
                        .padding(start = if (conversation.isPinned) 0.dp else DrawerMetrics.ActiveDotGap)
                        .size(DrawerMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .then(
                            if (conversation.isActiveRun) {
                                Modifier.background(MiuixTheme.colorScheme.primary)
                            } else {
                                Modifier.border(1.dp, MiuixTheme.colorScheme.primary, CircleShape)
                            },
                        ),
                )
            }
            if (conversation.isPinned) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = null,
                    modifier = Modifier.padding(
                        start = if (conversation.isActiveRun || conversation.hasCompletionMarker) DrawerMetrics.ActiveDotGap else 0.dp,
                    ).size(14.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
        }

        EtaDropdownMenu(
            expanded = showActionMenu,
            onDismissRequest = { showActionMenu = false },
            offset = DpOffset(x = 8.dp, y = 8.dp),
            alignEnd = true,
        ) {
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = {
                    Text(
                        stringResource(
                            if (conversation.isPinned) R.string.conversation_unpin
                            else R.string.conversation_pin,
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (conversation.isPinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.conversation_export)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onExport()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.conversation_export_markdown)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onExportMarkdown()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(stringResource(R.string.drawer_move_to_folder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                    )
                },
                onClick = {
                    showActionMenu = false
                    onMoveToFolder()
                },
            )
            DropdownMenuItem(
                modifier = DrawerMenuItemModifier,
                contentPadding = DrawerMenuItemPadding,
                text = { Text(text = stringResource(R.string.action_delete), color = MiuixTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                        tint = MiuixTheme.colorScheme.error,
                    )
                },
                onClick = {
                    showActionMenu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            horizontal = DrawerMetrics.RowHorizontalPadding,
            vertical = DrawerMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneFooterBar(
    assistantName: String,
    avatar: @Composable () -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenScheduledTasks: () -> Unit = {},
) {
    val view = LocalView.current
    var showShortcutsMenu by remember { mutableStateOf(false) }
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val couiDark = if (isCoui) CouiTheme.isDark else false
    val circleBg = if (isCoui) {
        if (couiDark) Color(0xFF22242A) else Color.White
    } else {
        MiuixTheme.colorScheme.primaryContainer
    }
    val circleBorder = if (isCoui) {
        if (couiDark) Color(0x20FFFFFF) else Color(0x14000000)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 左侧：当前助手卡片（头像居左 + 名称 + 上下切换提示，参考 DSH Go 底部状态区）
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    TouchHaptics.click(view)
                    onOpenAssistants()
                }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            avatar()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = assistantName,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primary),
                    )
                    Text(
                        text = "当前助手",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.UnfoldMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        // 右侧 1：九宫格快捷入口（收纳模型/Skills/定时任务/用量统计/权限健康，带文字标签更直观）
        Box {
            DrawerFooterCircleButton(
                icon = Icons.Rounded.Apps,
                label = "快捷入口",
                background = circleBg,
                borderColor = circleBorder,
                onClick = { showShortcutsMenu = true },
            )
            EtaDropdownMenu(
                expanded = showShortcutsMenu,
                onDismissRequest = { showShortcutsMenu = false },
                preferAbove = true,
                alignEnd = true,
                offset = DpOffset(x = 0.dp, y = 8.dp),
            ) {
                DropdownMenuItem(
                    modifier = DrawerMenuItemModifier,
                    contentPadding = DrawerMenuItemPadding,
                    text = { Text(stringResource(R.string.conversation_dock_models)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                    onClick = {
                        showShortcutsMenu = false
                        onOpenModelProviders()
                    },
                )
                DropdownMenuItem(
                    modifier = DrawerMenuItemModifier,
                    contentPadding = DrawerMenuItemPadding,
                    text = { Text(stringResource(R.string.route_skills)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Extension,
                            contentDescription = null,
                            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                    onClick = {
                        showShortcutsMenu = false
                        onOpenSkills()
                    },
                )
                DropdownMenuItem(
                    modifier = DrawerMenuItemModifier,
                    contentPadding = DrawerMenuItemPadding,
                    text = { Text(stringResource(R.string.route_scheduled_tasks)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                    onClick = {
                        showShortcutsMenu = false
                        onOpenScheduledTasks()
                    },
                )
                DropdownMenuItem(
                    modifier = DrawerMenuItemModifier,
                    contentPadding = DrawerMenuItemPadding,
                    text = { Text(stringResource(R.string.stats_page_title)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.BarChart,
                            contentDescription = null,
                            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                    onClick = {
                        showShortcutsMenu = false
                        onOpenUsageStats()
                    },
                )
                DropdownMenuItem(
                    modifier = DrawerMenuItemModifier,
                    contentPadding = DrawerMenuItemPadding,
                    text = { Text(stringResource(R.string.ui_permission_health_3048bb)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.HealthAndSafety,
                            contentDescription = null,
                            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                    onClick = {
                        showShortcutsMenu = false
                        onOpenPermissions()
                    },
                )
            }
        }

        // 右侧 2：设置圆形按钮
        DrawerFooterCircleButton(
            icon = if (isCoui) CouiIcons.Outlined.Setting else Icons.Rounded.Settings,
            label = stringResource(R.string.route_settings),
            background = circleBg,
            borderColor = circleBorder,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun DrawerFooterCircleButton(
    icon: ImageVector,
    label: String,
    background: Color,
    borderColor: Color,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (borderColor != Color.Transparent) Modifier.border(0.5.dp, borderColor, CircleShape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                TouchHaptics.click(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private val DrawerMotionEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

private val DrawerOpenMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = DrawerMetrics.OpenDurationMs,
    easing = DrawerMotionEasing,
)

private val DrawerCloseMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = DrawerMetrics.CloseDurationMs,
    easing = DrawerMotionEasing,
)

private object DrawerMotionSetters {
    private val drawerStateClass = DrawerState::class.java
    private val openSetter = method("setOpenDrawerMotionSpec\$material3")
    private val closeSetter = method("setCloseDrawerMotionSpec\$material3")

    private fun method(name: String) = runCatching {
        drawerStateClass.getMethod(name, FiniteAnimationSpec::class.java)
    }.getOrNull()

    fun apply(state: DrawerState) {
        // 只改菜单按钮触发的开合。手势松手后的 settle 沿用 Material3 默认弹簧，
        // 才能吃进快滑速度；短 tween 会把惯性掐成匀速，看起来就是顿一下。
        openSetter?.invoke(state, DrawerOpenMotion)
        closeSetter?.invoke(state, DrawerCloseMotion)
    }
}

private fun DrawerState.applyEtaDrawerMotion() {
    DrawerMotionSetters.apply(this)
}

internal data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
) {
    val key: String get() = "section-$section"
}

internal sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

internal fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    // A cached label or a differently ordered input can put the same section in disjoint
    // runs. Merge all of them, keeping the first section occurrence and row order stable.
    val groups = linkedMapOf<ConversationDrawerSection, MutableList<ConversationSummaryUi>>()
    for (conversation in this) {
        groups.getOrPut(conversation.drawerSection()) { mutableListOf() } += conversation
    }
    return groups.map { (section, items) -> ConversationDrawerGroup(section, items.toList()) }
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isToday -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}
