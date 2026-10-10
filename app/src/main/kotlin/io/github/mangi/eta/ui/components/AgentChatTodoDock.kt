package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.ConversationTodo
import io.github.mangi.eta.data.db.ConversationTodoStatus
import io.github.mangi.eta.data.repository.ConversationTodoRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 当前进行到第几步：优先 in_progress，其次第一个 pending；全部终态时等于总数。 */
internal fun currentTodoStep(todos: List<ConversationTodo>): Int {
    val inProgressIndex = todos.indexOfFirst { it.status == ConversationTodoStatus.IN_PROGRESS }
    val currentIndex =
        if (inProgressIndex >= 0) {
            inProgressIndex
        } else {
            todos.indexOfFirst { it.status == ConversationTodoStatus.PENDING }
        }
    return if (currentIndex >= 0) currentIndex + 1 else todos.size
}

/**
 * 会话 Todo 清单的常驻胶囊：显示「第 N / M 步」与进度圆环，点开是完整清单。
 *
 * 数据直接来自仓库（按会话 id 观察），全部项终态时先收起、再在短暂停留后整条隐藏；清单为空时不占位。
 * 本轮结束后清单仍未到终态时，按「已结束 · N 项未完成」收起并在 AUTO_HIDE_MS 后隐藏；
 * 会话超过 [TODO_DOCK_STALE_MS] 没有任何更新时按「已过期的计划」同样处理，
 * 避免模型漏写终态后胶囊永远停在界面上。
 *
 * 隐藏的同时会把该会话的清单行销毁：只隐藏组件的话，重新打开会话时会重新组合并再弹一次。
 * 移植自 Operit-Ry 的 ChatTodoDock，位置与交互保持一致（输入框正上方）。
 */
@Composable
internal fun AgentChatTodoDock(
    conversationId: String?,
    modifier: Modifier = Modifier,
    runActive: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val repository = remember(context) { ConversationTodoRepository.getInstance(context) }
    val todos by remember(conversationId, repository) {
        if (conversationId.isNullOrBlank()) {
            flowOf(emptyList<ConversationTodo>())
        } else {
            repository.observe(conversationId)
        }
    }.collectAsState(initial = emptyList<ConversationTodo>())

    // 会话最后活动时间：清单卡住时用它判断这份计划是不是早就没人动了。
    val lastActivityAt by remember(conversationId, repository) {
        if (conversationId.isNullOrBlank()) {
            flowOf(null)
        } else {
            repository.observeConversationUpdatedAt(conversationId)
        }
    }.collectAsState(initial = null)

    val hasTodos = !conversationId.isNullOrBlank() && todos.isNotEmpty()
    var expanded by rememberSaveable(conversationId) { mutableStateOf(false) }
    // 全部完成/取消后，胶囊先收起，再在 AUTO_HIDE_MS 后整条隐藏；新一轮清单会立刻重新出现。
    var autoHidden by remember(conversationId) { mutableStateOf(false) }
    val allTerminal = todos.isNotEmpty() && todos.all {
        it.status == ConversationTodoStatus.COMPLETED || it.status == ConversationTodoStatus.CANCELLED
    }
    // 过期判定：清单里还有 in_progress，而会话已经 STALE_MS 没有任何更新（模型漏写终态时会一直卡在界面上）。
    var now by remember(conversationId) { mutableStateOf(System.currentTimeMillis()) }
    val expired = todoDockExpired(todos, lastActivityAt, now, runActive)
    // 本轮已经结束、清单还没到终态：模型漏了收尾，胶囊不能一直挂着等下一次交互。
    val runEnded = todoDockUnfinished(todos, runActive)
    LaunchedEffect(conversationId, todos, lastActivityAt, runActive) {
        // 只在可能过期时启动分钟级巡检：清单进入终态或判定过期就停，不做常驻轮询。
        if (allTerminal || lastActivityAt == null) return@LaunchedEffect
        while (!todoDockExpired(todos, lastActivityAt, System.currentTimeMillis(), runActive)) {
            delay(TODO_DOCK_STALE_POLL_MS)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }
    LaunchedEffect(conversationId, todos, allTerminal, expired, runEnded) {
        if (allTerminal || expired || runEnded) {
            expanded = false
            delay(TODO_DOCK_AUTO_HIDE_MS)
            // 收起动画走完就把这份清单销毁：只隐藏组件的话，重开会话时会重新组合、又弹一次。
            // 清单已经收尾/过期，留着没有价值；下一轮任务里模型写新快照会自动重建。
            runCatching { repository.clear(conversationId) }
            autoHidden = true
        } else {
            autoHidden = false
        }
    }

    if (!hasTodos || autoHidden) {
        if (leadingContent != null) {
            Column(
                modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                leadingContent()
            }
        }
        return
    }

    val terminalCount = todos.count {
        it.status == ConversationTodoStatus.COMPLETED || it.status == ConversationTodoStatus.CANCELLED
    }
    val progress = terminalCount.toFloat() / todos.size.toFloat()
    val step = currentTodoStep(todos)
    val toggleLabel = stringResource(R.string.chat_todo_toggle_desc)

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                todos.forEach { todo -> TodoDetailRow(todo) }
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { expanded = !expanded }
                    .semantics { contentDescription = toggleLabel }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    progress = progress,
                    colors = ProgressIndicatorDefaults.progressIndicatorColors(
                        foregroundColor = MiuixTheme.colorScheme.primary,
                        disabledForegroundColor = MiuixTheme.colorScheme.primary,
                        backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                    ),
                    strokeWidth = 2.dp,
                    size = 14.dp,
                )
                Text(
                    text = when {
                        expired -> stringResource(R.string.chat_todo_expired)
                        runEnded -> stringResource(R.string.chat_todo_unfinished, todos.size - terminalCount)
                        else -> stringResource(R.string.chat_todo_step_progress, step, todos.size)
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            leadingContent?.let { content ->
                Box(Modifier.align(Alignment.CenterStart)) { content() }
            }
        }
        Spacer(Modifier.size(6.dp))
    }
}

@Composable
private fun TodoDetailRow(todo: ConversationTodo) {
    val terminal =
        todo.status == ConversationTodoStatus.COMPLETED || todo.status == ConversationTodoStatus.CANCELLED
    val indicatorColor = when (todo.status) {
        ConversationTodoStatus.IN_PROGRESS -> MiuixTheme.colorScheme.primary
        ConversationTodoStatus.COMPLETED -> MiuixTheme.colorScheme.primary
        ConversationTodoStatus.CANCELLED -> MiuixTheme.colorScheme.outline
        ConversationTodoStatus.PENDING -> MiuixTheme.colorScheme.outline
    }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.then(if (terminal) Modifier.alpha(0.66f) else Modifier),
    ) {
        Box(
            modifier = Modifier.padding(top = 3.dp).size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(14.dp)) {
                drawCircle(color = indicatorColor, style = Stroke(width = 1.8.dp.toPx()))
                if (todo.status == ConversationTodoStatus.COMPLETED) {
                    drawCircle(color = indicatorColor, radius = 3.dp.toPx())
                }
            }
        }
        Text(
            text = todo.content,
            style = MiuixTheme.textStyles.body2,
            color = if (todo.status == ConversationTodoStatus.IN_PROGRESS) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            textDecoration = if (terminal) TextDecoration.LineThrough else TextDecoration.None,
        )
    }
}

/**
 * 过期判定：本轮没有在跑（[runActive] 为 false），清单还没到终态，而会话已超过
 * [TODO_DOCK_STALE_MS] 没有任何更新。
 *
 * 收尾前没把清单更新到终态是模型常见漏项，只按 in_progress 判会让「只剩 pending」的清单永久驻留。
 * [lastActivityAt] 为 null（读不到会话）时按未过期处理，宁可留着也不误伤正在跑的任务。
 */
internal fun todoDockExpired(
    todos: List<ConversationTodo>,
    lastActivityAt: Long?,
    now: Long,
    runActive: Boolean,
): Boolean =
    !runActive &&
        todos.isNotEmpty() &&
        todos.any {
            it.status == ConversationTodoStatus.PENDING || it.status == ConversationTodoStatus.IN_PROGRESS
        } &&
        lastActivityAt != null &&
        lastActivityAt > 0L &&
        now - lastActivityAt >= TODO_DOCK_STALE_MS

/**
 * 本轮已结束（没有在跑）而清单还没到终态：模型漏了收尾。
 *
 * 与 [todoDockExpired] 的区别：那条看「多久没动」，这条只看「本轮结束没结束」，
 * 所以用户接着聊天也不会让胶囊永久驻留——这正是「最后一步一直卡着」的来源。
 */
internal fun todoDockUnfinished(todos: List<ConversationTodo>, runActive: Boolean): Boolean =
    !runActive && todos.any {
        it.status == ConversationTodoStatus.PENDING || it.status == ConversationTodoStatus.IN_PROGRESS
    }

/** 任务已结束、清单却停在该时长前没动过，视为「已过期的计划」。 */
internal const val TODO_DOCK_STALE_MS = 10 * 60 * 1000L

/** 过期巡检间隔：判定靠会话最后活动时间，巡检只负责把「现在」推进到过期点。 */
private const val TODO_DOCK_STALE_POLL_MS = 60_000L

/** 全部项终态后胶囊继续停留的时长；之后整条隐藏。 */
private const val TODO_DOCK_AUTO_HIDE_MS = 6_000L
