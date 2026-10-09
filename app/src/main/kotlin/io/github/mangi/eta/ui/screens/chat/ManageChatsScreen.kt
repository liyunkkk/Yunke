package io.github.mangi.eta.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.app.SearchHistoryDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.MessageSearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog


/** 管理页列表顺序。已有会话保持原位；新会话按当前列表里的相对顺序插到最前。置顶变化才整表重排。 */
internal fun stableManageChatOrder(
    previous: List<String>,
    current: List<String>,
    reorder: Boolean = false,
): List<String> {
    if (previous.isEmpty() || reorder) return current
    val present = current.toSet()
    val kept = previous.filter { it in present }
    val keptSet = kept.toSet()
    return current.filter { it !in keptSet } + kept
}

@Composable
internal fun ManageChatsScreen(
    conversations: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteConversation: (ConversationSummaryUi) -> Unit,
    onDeleteAll: () -> Unit,
    onSearchHistory: suspend (String) -> List<MessageSearchHit> = { emptyList() },
    onOpenHistoryHit: (MessageSearchHit) -> Unit = {},
) {
    var showDeleteAll by remember { mutableStateOf(false) }
    var showSearchHistory by remember { mutableStateOf(false) }
    // 多个会话同时在跑时，更新时间会不断把它们换到前面。管理页按打开时的顺序钉住，
    // 只在原地刷新标题和状态，滑动删除才点得中。新出现的会话插到最前，关掉页面再打开才重排。
    var orderIds by remember { mutableStateOf(conversations.map { it.id }) }
    val pinnedIds = conversations.filter { it.isPinned }.map { it.id }
    var pinnedSnapshot by remember { mutableStateOf(pinnedIds) }
    val pinnedChanged = pinnedIds.toSet() != pinnedSnapshot.toSet()
    val nextOrder = stableManageChatOrder(orderIds, conversations.map { it.id }, pinnedChanged)
    SideEffect {
        if (nextOrder != orderIds || pinnedChanged) {
            orderIds = nextOrder
            pinnedSnapshot = pinnedIds
        }
    }
    val byId = conversations.associateBy { it.id }
    val ordered = nextOrder.mapNotNull { byId[it] }

    MiuixScaffoldPage(
        title = stringResource(R.string.history_page_title),
        onBack = onBack,
        listModifier = Modifier.streamDiagnosticMeasure("manage.lazy.measure").streamDiagnosticPlacement("manage.lazy.place").streamDiagnosticDraw("manage.lazy.draw"),
        actions = {
            IconButton(onClick = { showSearchHistory = true }) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.action_search_history),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = { if (conversations.isNotEmpty()) showDeleteAll = true }) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.history_page_delete_all),
                    tint = MiuixTheme.colorScheme.error,
                )
            }
        },
    ) {
        if (ordered.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.history_page_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        } else {
            ordered.forEach { conversation ->
                item(key = conversation.id) {
                    SwipeableManageChatRow(
                        conversation = conversation,
                        onClick = { onOpenConversation(conversation.id) },
                        onTogglePin = { onTogglePin(conversation.id) },
                        onDelete = { onDeleteConversation(conversation) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = tween(180),
                            placementSpec = null,
                        ),
                    )
                }
            }
        }
    }

    SearchHistoryDialog(
        show = showSearchHistory,
        onDismiss = { showSearchHistory = false },
        onSearch = onSearchHistory,
        onOpenHit = { hit ->
            showSearchHistory = false
            onOpenHistoryHit(hit)
        },
    )

    if (showDeleteAll) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.history_page_delete_all_title),
            summary = stringResource(R.string.history_page_delete_all_message),
            onDismissRequest = { showDeleteAll = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { showDeleteAll = false },
                onConfirm = {
                    onDeleteAll()
                    showDeleteAll = false
                },
            )
        }
    }
}

@Composable
private fun SwipeableManageChatRow(
    conversation: ConversationSummaryUi,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.4f },
    )
    var collapsing by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }
    var crossedDeleteThreshold by remember { mutableStateOf(false) }

    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.targetValue }.collectLatest { target ->
            val crossed = target == SwipeToDismissBoxValue.EndToStart
            if (crossed && !crossedDeleteThreshold) {
                crossedDeleteThreshold = true
                TouchHaptics.gestureThreshold(view)
            } else if (!crossed) {
                crossedDeleteThreshold = false
            }
        }
    }

    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.settledValue }.collectLatest { settled ->
            if (!collapsing && settled == SwipeToDismissBoxValue.EndToStart) {
                collapsing = true
            }
        }
    }

    LaunchedEffect(collapsing) {
        if (!collapsing || deleted) return@LaunchedEffect
        delay(300)
        deleted = true
        onDelete()
    }

    AnimatedVisibility(
        visible = !collapsing,
        modifier = modifier,
        exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            modifier = Modifier
                .manageChatDismissDoesNotClaimPageBack()
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(18.dp)),
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MiuixTheme.colorScheme.onErrorContainer,
                    )
                }
            },
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                ManageChatRow(
                    conversation = conversation,
                    onClick = onClick,
                    onTogglePin = onTogglePin,
                )
            }
        }
    }
}

@Composable
private fun ManageChatRow(
    conversation: ConversationSummaryUi,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = conversation.title.ifBlank { conversation.preview },
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.isActiveRun || conversation.hasCompletionMarker) {
                    Box(
                        modifier = Modifier.size(6.dp).clip(CircleShape).then(
                            if (conversation.isActiveRun) {
                                Modifier.background(MiuixTheme.colorScheme.primary)
                            } else {
                                Modifier.border(1.dp, MiuixTheme.colorScheme.primary, CircleShape)
                            },
                        ),
                    )
                }
            }
            Text(
                text = conversation.timeLabel,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = if (conversation.isPinned) {
                    Icons.Rounded.PushPin
                } else {
                    Icons.Outlined.PushPin
                },
                contentDescription = stringResource(
                    if (conversation.isPinned) {
                        R.string.conversation_unpin
                    } else {
                        R.string.conversation_pin
                    },
                ),
                modifier = Modifier.size(20.dp),
                tint = if (conversation.isPinned) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
    }
}

/** Keep the row's right-to-left delete gesture, but leave left-edge page-back to navigation. */
private fun Modifier.manageChatDismissDoesNotClaimPageBack(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position
        var claimedByDismiss = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.position - start
            if (!claimedByDismiss && (kotlin.math.abs(delta.x) > viewConfiguration.touchSlop ||
                    kotlin.math.abs(delta.y) > viewConfiguration.touchSlop)) {
                claimedByDismiss = delta.x < 0f && kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)
            }
            if (!claimedByDismiss) change.consume()
        }
    }
}
