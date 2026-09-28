package io.github.mangi.eta.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.mangi.eta.agent.runtime.AgentChildRunControl

/** The parent is already ending and its captured children are already paused before this opens. */
@Composable
internal fun AgentStopTaskDialog(
    onPauseChildren: () -> Unit,
    onStopChildren: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("如何处理子代理？") },
        text = {
            Text("主回复已结束，未完成的子代理已请求暂停。保留暂停可供之后查询并继续；停止也会保留已有结果。返回或关闭默认保持暂停。")
        },
        confirmButton = {
            TextButton(onClick = onStopChildren) { Text("停止子代理") }
        },
        dismissButton = {
            TextButton(onClick = onPauseChildren) { Text("暂停子代理") }
        },
    )
}

/**
 * Mount once in a visible conversation host, not on the old background-task management button.
 * Absence of this host never resolves a choice. A new turn does not invalidate an old event, and
 * confirming an old event never captures the current generation or replays a setting change.
 */
@Composable
internal fun AgentPendingChildStopDialog(ownerId: String?) {
    val pending by AgentChildRunControl.pendingSelections.collectAsState()
    val selection = pending.firstOrNull { choice ->
        ownerId != null && choice.targets.any { it.ownerId == ownerId }
    } ?: return
    AgentStopTaskDialog(
        onPauseChildren = { AgentChildRunControl.resolve(selection.eventId) },
        onStopChildren = { AgentChildRunControl.resolve(selection.eventId, stopChildren = true) },
        onDismiss = { AgentChildRunControl.resolve(selection.eventId) },
    )
}

/** Legacy snapshot value retained for callers migrating away from the removed range dialog. */
internal data class AgentStopSelection<T>(
    val ownerId: String,
    val runId: String?,
    val groupTarget: T?,
) {
    fun stillCurrent(ownerId: String?, runId: String?, groupTarget: T?): Boolean =
        this.ownerId == ownerId && this.runId == runId && this.groupTarget == groupTarget
}
