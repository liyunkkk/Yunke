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
 * 移植自 Operit-Ry 的 ChatTodoDock，位置与交互保持一致（输入框正上方）。
 */
@Composable
internal fun AgentChatTodoDock(
    conversationId: String?,
    modifier: Modifier = Modifier,
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

    val hasTodos = !conversationId.isNullOrBlank() && todos.isNotEmpty()
    var expanded by rememberSaveable(conversationId) { mutableStateOf(false) }
    // 全部完成/取消后，胶囊先收起，再在 AUTO_HIDE_MS 后整条隐藏；新一轮清单会立刻重新出现。
    var autoHidden by remember(conversationId) { mutableStateOf(false) }
    val allTerminal = todos.isNotEmpty() && todos.all {
        it.status == ConversationTodoStatus.COMPLETED || it.status == ConversationTodoStatus.CANCELLED
    }
    LaunchedEffect(conversationId, todos) {
        if (allTerminal) {
            expanded = false
            delay(TODO_DOCK_AUTO_HIDE_MS)
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
                    text = stringResource(R.string.chat_todo_step_progress, step, todos.size),
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

/** 全部项终态后胶囊继续停留的时长；之后整条隐藏。 */
private const val TODO_DOCK_AUTO_HIDE_MS = 6_000L
