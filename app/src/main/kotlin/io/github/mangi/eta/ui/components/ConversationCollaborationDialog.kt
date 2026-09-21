package io.github.mangi.eta.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.haptics.TouchHaptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationCollaborationDialog(
    show: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    taskRunning: Boolean = false,
) {
    if (!show) return
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val profiles by remember { SubAgentPreferences.profilesFlow() }.collectAsState(initial = SubAgentPreferences.profiles())
    val currentRunning by rememberUpdatedState(taskRunning)
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(240).dp
    val view = LocalView.current
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maximumHeight),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                    Text("本会话协作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 18.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .alpha(if (taskRunning) 0.38f else 1f)
                        .pointerInput(taskRunning) {
                            if (taskRunning) awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .toggleable(value = enabled, enabled = !taskRunning, role = Role.Switch,
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onValueChange = { if (!currentRunning) { TouchHaptics.click(view); onEnabledChange(it) } }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("自动委派", style = MaterialTheme.typography.bodyLarge)
                                Text("按职责自动分配任务", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                            Switch(checked = enabled, enabled = !taskRunning, onCheckedChange = null)
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        profiles.forEach { profile ->
                            key(profile.id) {
                                SubAgentProfileRow(profile, providers, enabled = !taskRunning)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                            }
                        }
                        Text("点按模型切换 · 长按调整思考", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !taskRunning, onClick = { if (!currentRunning) { TouchHaptics.click(view); onDismiss() } }) { Text("完成") }
                    }
                }
            }
        }
    }
    }
}
