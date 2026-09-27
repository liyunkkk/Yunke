package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Inline controls on the Agent task preference page. Reading status never changes the display. */
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
    val summary = when {
        snapshot == null -> stringResource(R.string.vd_recovery_working)
        !snapshot.optBoolean("ok") -> stringResource(R.string.vd_recovery_unknown)
        !snapshot.optBoolean("present") -> stringResource(R.string.vd_recovery_empty)
        snapshot.optBoolean("busy") -> stringResource(R.string.vd_recovery_busy)
        else -> stringResource(R.string.vd_recovery_record, snapshot.optInt("displayId", -1),
            snapshot.optString("phase", "recovery_pending"))
    }
    BackHandler(enabled = working) { /* Do not abandon an in-flight recovery action. */ }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(summary)
        val lastError = snapshot?.optString("lastError").orEmpty()
        if (lastError.isNotBlank()) {
            Text(lastError)
            val detail = snapshot?.optString("lastDetail").orEmpty()
            if (detail.isNotBlank()) Text(detail)
        }
        result?.let { receipt ->
            if (receipt.optBoolean("ok") && receipt.optBoolean("released")) {
                Text(stringResource(R.string.vd_recovery_success))
            } else {
                Text(stringResource(R.string.vd_recovery_failed, receipt.optString("error", "UNKNOWN")))
                if (receipt.optString("message").isNotBlank()) Text(receipt.optString("message"))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.vd_preview_open),
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp),
                minWidth = 0.dp,
                insideMargin = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                textStyle = MiuixTheme.textStyles.button.copy(textAlign = TextAlign.Center),
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
            )
            TextButton(
                text = stringResource(R.string.vd_recovery_refresh),
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp),
                minWidth = 0.dp,
                insideMargin = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                textStyle = MiuixTheme.textStyles.button.copy(textAlign = TextAlign.Center),
                enabled = installed == true && !working,
                onClick = { TouchHaptics.click(view); refresh() },
            )
            TextButton(
                text = stringResource(if (working) R.string.vd_recovery_working else R.string.vd_recovery_action),
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp),
                minWidth = 0.dp,
                insideMargin = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                textStyle = MiuixTheme.textStyles.button.copy(textAlign = TextAlign.Center),
                enabled = installed == true && !working && snapshot != null && snapshot.optBoolean("ok") &&
                    snapshot.optBoolean("present") && snapshot.optBoolean("recoverable"),
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
            )
        }
    }
}
