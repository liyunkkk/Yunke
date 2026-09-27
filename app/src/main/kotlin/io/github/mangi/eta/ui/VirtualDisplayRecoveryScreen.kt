package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import io.github.mangi.eta.agent.device.VirtualDisplayWebPreview
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Inline controls on the Agent task preference page. Reading status never changes the display.
 * 只有存在恢复记录（或读取失败、刚结束一次操作）时才显示卡片，避免把内部状态码当正文展示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VirtualDisplayRecoveryControls(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
    readStatus: (Context) -> JSONObject = VirtualDisplaySession::recoveryStatus,
    recover: (Context) -> JSONObject = VirtualDisplaySession::recoverAndFinishManually,
    onWorkingChanged: (Boolean) -> Unit = {},
) {
    val installed = rememberTaskBackendInstalled()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var working by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }

    fun setWorking(value: Boolean) {
        working = value
        onWorkingChanged(value)
    }

    LaunchedEffect(installed, working) {
        if (installed == false && !working) VirtualDisplayWebPreview.stop()
    }

    fun refresh() {
        if (working) return
        setWorking(true)
        scope.launch {
            try {
                state = withContext(Dispatchers.IO) { readStatus(context.applicationContext) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                state = JSONObject().put("ok", false).put("error", "RECOVERY_STATE_UNREADABLE")
            } finally { setWorking(false) }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A browser pauses the page; stop only when the controls really leave composition.
    DisposableEffect(Unit) {
        onDispose {
            VirtualDisplayWebPreview.stop()
            onWorkingChanged(false)
        }
    }

    val snapshot = state
    BackHandler(enabled = working) { /* Do not abandon an in-flight recovery action. */ }

    val readable = snapshot?.optBoolean("ok") == true
    val present = snapshot?.optBoolean("present") == true
    val busy = snapshot?.optBoolean("busy") == true
    val phase = snapshot?.optString("phase", RECOVERY_PHASE_PENDING) ?: RECOVERY_PHASE_PENDING
    // 没有恢复记录时不显示这张卡片；读取失败和刚结束的操作仍需可见，便于排查与确认。
    if (result == null && !(snapshot != null && (!readable || present))) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.agent_task_preference_recovery_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (working) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        text = stringResource(recoveryStatusRes(readable, busy, phase)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (present) {
                    Text(
                        text = stringResource(
                            R.string.agent_task_preference_recovery_display_id,
                            snapshot?.optInt("displayId", -1) ?: -1,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (readable && present && !busy && phase != RECOVERY_PHASE_PENDING) {
                    Text(
                        text = stringResource(R.string.agent_task_preference_recovery_raw_phase, phase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val lastError = snapshot?.optString("lastError").orEmpty()
                if (lastError.isNotBlank()) {
                    Text(
                        text = lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val detail = snapshot?.optString("lastDetail").orEmpty()
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                result?.let { receipt ->
                    if (receipt.optBoolean("ok") && receipt.optBoolean("released")) {
                        Text(
                            text = stringResource(R.string.vd_recovery_success),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            text = stringResource(
                                R.string.vd_recovery_failed,
                                receipt.optString("error", "UNKNOWN"),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        val message = receipt.optString("message")
                        if (message.isNotBlank()) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = installed == true && !working && snapshot?.optBoolean("present") == true,
                        onClick = {
                            if (!working) {
                                TouchHaptics.click(view)
                                setWorking(true)
                                scope.launch {
                                    try {
                                        val uri = withContext(Dispatchers.IO) { VirtualDisplayWebPreview.openWithManualClose(context) }
                                        val stillInstalled = withContext(Dispatchers.IO) { AgentTaskSurface.moduleInstalled() }
                                        check(stillInstalled) { "Backend module removed" }
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (ex: CancellationException) {
                                        VirtualDisplayWebPreview.stop()
                                        throw ex
                                    } catch (_: Exception) {
                                        VirtualDisplayWebPreview.stop()
                                        result = JSONObject().put("ok", false).put("error", "WEB_PREVIEW_OPEN_FAILED")
                                    } finally { setWorking(false) }
                                }
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.vd_preview_open),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        enabled = installed == true && !working,
                        onClick = { TouchHaptics.click(view); refresh() },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.vd_recovery_refresh),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        enabled = installed == true && !working && snapshot != null &&
                            snapshot.optBoolean("ok") && snapshot.optBoolean("present") &&
                            snapshot.optBoolean("recoverable"),
                        onClick = {
                            if (!working) {
                                TouchHaptics.click(view)
                                setWorking(true)
                                result = null
                                scope.launch {
                                    try {
                                        val receipt = withContext(Dispatchers.IO) { recover(context.applicationContext) }
                                        result = receipt
                                        state = withContext(Dispatchers.IO) { readStatus(context.applicationContext) }
                                    } catch (ex: CancellationException) {
                                        throw ex
                                    } catch (_: Exception) {
                                        result = JSONObject().put("ok", false).put("error", "RECOVERY_OPERATION_FAILED")
                                    } finally { setWorking(false) }
                                }
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(if (working) R.string.vd_recovery_working else R.string.vd_recovery_action),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private const val RECOVERY_PHASE_PENDING = "recovery_pending"

/** 只有"等待恢复"是已确认可读的状态码；其余码显示为状态未知，原始码留在次级文字里。 */
private fun recoveryStatusRes(readable: Boolean, busy: Boolean, phase: String): Int = when {
    !readable -> R.string.vd_recovery_unknown
    busy -> R.string.vd_recovery_busy
    phase == RECOVERY_PHASE_PENDING -> R.string.agent_task_preference_recovery_phase_waiting
    else -> R.string.agent_task_preference_recovery_phase_unknown
}
