package io.github.mangi.eta.ui

import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.SpeechModelManifest
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.components.Card
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import io.github.mangi.eta.ui.components.AdaptiveConfirmDialog
import io.github.mangi.eta.ui.components.coui.EtaLinearProgressIndicator

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by OfflineSpeechPack.state.collectAsState()
    val config by DoubaoVoiceConfig.state.collectAsState()
    var page by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    if (page != null) {
        DoubaoVoiceSettings(page = page!!, onBack = { page = null })
        return
    }
    var confirmDownload by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { DoubaoVoiceConfig.load(context); OfflineSpeechPack.initialize(context) }
    VoiceSettingsSectionPage(title = stringResource(R.string.speech_title), onBack = onBack) {
        item(key = "speech_enable") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.speech_enable),
                    summary = "点击聊天输入框的语音按钮，把说话变成文字。",
                    insideMargin = PaddingValues(16.dp),
                    checked = config.inputEnabled,
                    enabled = true,
                    onCheckedChange = { enabled ->
                        TouchHaptics.click(view)
                        DoubaoVoiceConfig.save(context, config.copy(inputEnabled = enabled))
                    },
                )
            }
        }
        item(key = "speech_pack") {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(when {
                        state.downloading -> R.string.speech_cancel_download
                        state.ready -> R.string.speech_pack_ready
                        else -> R.string.speech_download
                    }),
                    summary = stringResource(when {
                        state.checking -> R.string.speech_checking
                        state.error -> R.string.speech_download_failed
                        else -> R.string.speech_pack_summary
                    }),
                    insideMargin = PaddingValues(16.dp),
                    enabled = !state.checking && !state.ready,
                    onClick = {
                        TouchHaptics.click(view)
                        if (state.downloading) OfflineSpeechPack.cancelDownload() else confirmDownload = true
                    },
                )
                if (state.downloading) {
                    val progress = (state.downloadedBytes.toFloat() / SpeechModelManifest.totalBytes).coerceIn(0f, 1f)
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        EtaLinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item(key = "recognition_method") {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(title = "识别方式", summary = if (config.cloudAsr) "豆包识别 · 需要联网" else "本机识别 · 无需账户",
                    insideMargin = PaddingValues(16.dp), onClick = { TouchHaptics.click(view); page = "asr" })
            }
        }

    }
    AdaptiveConfirmDialog(
        show = confirmDownload,
        title = stringResource(R.string.speech_download),
        summary = stringResource(R.string.speech_download_confirm),
        onDismissRequest = { confirmDownload = false },
        confirmText = stringResource(R.string.speech_download),
        cancelText = stringResource(R.string.action_cancel),
        onCancel = { confirmDownload = false },
        onConfirm = { confirmDownload = false; OfflineSpeechPack.download(context) },
    )
}
