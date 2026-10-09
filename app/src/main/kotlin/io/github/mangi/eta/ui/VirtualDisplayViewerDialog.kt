package io.github.mangi.eta.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 副屏只读镜像页：显示画面、运行状态与最近操作轨迹。
 *
 * 刻意不提供任何输入通道：人一旦能在副屏上直接操作，模型手里的节点索引与坐标就可能过期，
 * 那需要一整套「手动输入代次」失效机制。本页只读，不改变任何观察状态。
 */
@Composable
internal fun VirtualDisplayViewerDialog(onDismiss: () -> Unit) {
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var actions by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                val nextStatus = runCatching { VirtualDisplaySession.viewerStatus() }.getOrNull()
                val nextFrame = runCatching { VirtualDisplaySession.viewerFrame() }.getOrNull()?.let { payload ->
                    val bytes = runCatching {
                        Base64.decode(payload.optString("data"), Base64.DEFAULT)
                    }.getOrNull()
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
                val nextActions = runCatching { VirtualDisplaySession.recentActions() }.getOrNull()?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }.orEmpty()
                Triple(nextStatus, nextFrame, nextActions)
            }
            status = snapshot.first
            frame = snapshot.second
            actions = snapshot.third
            delay(1_000L)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.vd_viewer_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val running = status?.optBoolean("running") == true
                Text(
                    if (running) {
                        stringResource(R.string.vd_viewer_status, status?.optInt("display_id", 0) ?: 0)
                    } else {
                        stringResource(R.string.vd_viewer_none)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                val bitmap = frame
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(stringResource(R.string.vd_viewer_no_frame))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.vd_viewer_actions), style = MaterialTheme.typography.titleSmall)
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(actions.asReversed()) { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.vd_viewer_close))
                }
            }
        }
    }
}
