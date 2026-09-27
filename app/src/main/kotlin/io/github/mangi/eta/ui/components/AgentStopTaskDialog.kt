package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Confirmation is owned by the caller. Dismissing this dialog never sends a stop command. */
@Composable
internal fun AgentStopTaskDialog(
    mainRunning: Boolean,
    childrenRunning: Boolean,
    onStopMain: () -> Unit,
    onStopAll: () -> Unit,
    onDismiss: () -> Unit,
    onPause: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("停止任务？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (mainRunning && childrenRunning) {
                        "主回复和后台子任务正在运行。请选择要停止的范围。"
                    } else if (childrenRunning) {
                        "主回复已结束，但后台子任务仍在运行。"
                    } else {
                        "主回复正在运行。"
                    },
                )
                if (mainRunning) {
                    Text("仅停止主回复不会取消已经派出的子代理，后台任务可能继续产生费用。")
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (mainRunning && onPause != null) {
                    TextButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                        Text("暂停主回复（可继续）")
                    }
                }
                if (mainRunning) {
                    TextButton(onClick = onStopMain, modifier = Modifier.fillMaxWidth()) {
                        Text("仅停止主回复")
                    }
                }
                TextButton(onClick = onStopAll, modifier = Modifier.fillMaxWidth()) {
                    Text("停止整个任务")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Immutable snapshot for a confirmation. The UI must compare its owner/run and the registry's
 * generation-bearing target again at confirmation time, not read a newly selected run as target.
 * For runs without children [groupTarget] is null; for child-only work [runId] is null.
 */
internal data class AgentStopSelection<T>(
    val ownerId: String,
    val runId: String?,
    val groupTarget: T?,
) {
    fun stillCurrent(ownerId: String?, runId: String?, groupTarget: T?): Boolean =
        this.ownerId == ownerId && this.runId == runId && this.groupTarget == groupTarget
}
