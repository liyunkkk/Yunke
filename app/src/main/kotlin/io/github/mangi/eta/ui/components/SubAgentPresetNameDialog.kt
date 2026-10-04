package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.window.WindowDialog

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
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            EtaFormTextField(
                value = name,
                onValueChange = { onNameChange(it.take(80)) },
                hint = fieldHint,
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(enabled = saveEnabled, onClick = onSave) { Text("保存") }
            }
        }
    }
}
