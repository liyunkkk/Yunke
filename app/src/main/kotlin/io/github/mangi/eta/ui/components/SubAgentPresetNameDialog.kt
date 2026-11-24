package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Use the app's window-level form host, also used for conversation names in AgentAppRoot.
 * Its DialogContentLayout supplies the form's bounds; the editable field is not placed in
 * Material AlertDialog's weighted supporting-text slot. Keep the shared outlined input and
 * Material text actions rather than introducing a different input or button style.
 */
@Composable
internal fun SubAgentPresetNameDialog(
    title: String,
    name: String,
    onNameChange: (String) -> Unit,
    saveEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    fieldHint: String = "组名称",
) {
    AdaptiveConfirmDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
        confirmText = "保存",
        cancelText = "取消",
        confirmEnabled = saveEnabled,
        onCancel = onDismiss,
        onConfirm = onSave,
    ) {
        Column(Modifier.fillMaxWidth()) {
            EtaFormTextField(
                value = name,
                onValueChange = { onNameChange(it.take(80)) },
                hint = fieldHint,
                singleLine = true,
            )
        }
    }
}
