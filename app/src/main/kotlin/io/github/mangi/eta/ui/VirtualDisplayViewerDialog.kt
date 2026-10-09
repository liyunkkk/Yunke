package io.github.mangi.eta.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 副屏只读镜像页：显示画面、运行状态与最近操作轨迹。
 *
 * 刻意不提供任何输入通道：人一旦能在副屏上直接操作，模型手里的节点索引与坐标就可能过期，
 * 那需要一整套「手动输入代次」失效机制。本页只读，不改变任何观察状态。
 *
 * 容器与排版跟全应用统一用 Miuix 的 WindowDialog + Card（此前是 Material 3 的 Dialog + Surface，
 * 默认形状是直角，和别处的圆角卡片明显割裂）；没有会话时给出这一页的用途说明，不留空白。
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
            // 2 秒一帧：比悬浮小窗（10 秒）跟手，又不至于和副屏的 GUI 操作长时间抢 owner 连接。
            delay(2_000L)
        }
    }

    val running = status?.optBoolean("running") == true
    WindowDialog(
        show = true,
        title = stringResource(R.string.vd_viewer_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (running) {
                    stringResource(R.string.vd_viewer_status, status?.optInt("display_id", 0) ?: 0)
                } else {
                    stringResource(R.string.vd_viewer_none)
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (!running) {
                // 没有会话时解释这一页是干什么的，而不是丢一块空白让人猜。
                Text(
                    text = stringResource(R.string.vd_viewer_none_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 420.dp)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = frame
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.vd_viewer_no_frame),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
                if (actions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.vd_viewer_actions),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                        items(actions) { action ->
                            Text(
                                text = action,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.vd_viewer_close))
            }
        }
    }
}
