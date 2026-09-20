package io.github.mangi.eta.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices
import io.github.mangi.eta.agent.voice.tts.ReadAloudVoiceHistory
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalVoicesScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf("") }
    if (page == "doubao") { DoubaoVoiceSettings("voices", onBack = { page = "" }); return }
    if (page == "mimo") { MimoVoicesScreen(onBack = { page = "" }); return }
    val view = LocalView.current
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("我的声音") }, navigationIcon = {
            IconButton(onClick = { TouchHaptics.click(view); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("doubao", "豆包语音", "导入音色名额、制作和试听声音"),
                Triple("mimo", "MiMo 语音", "导入参考录音、复刻试听和朗读")).forEach { (id, title, detail) ->
                OutlinedCard(onClick = { TouchHaptics.click(view); page = id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MimoVoicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val available = providers.filter(MimoPersonalVoices::supports)
    var selectedProvider by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID)) }
    val provider = available.firstOrNull { it.id == selectedProvider } ?: available.singleOrNull()
    var providerMenu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<MimoPersonalVoices.Voice?>(null) }
    var sample by remember { mutableStateOf("你好，这是我的 MiMo 个人声音试听。") }
    val voices by MimoPersonalVoices.state.collectAsState()
    val playback by SpeechPlayback.state.collectAsState()
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { MimoPersonalVoices.load(context) } }
            .onSuccess { ready = true }.onFailure { notice = "声音列表读取失败，请重试" }
    }
    DisposableEffect(Unit) { onDispose { if (SpeechPlayback.state.value.owner?.startsWith("mimo-preview:") == true) SpeechPlayback.stop() } }
    BackHandler(enabled = !busy, onBack = onBack)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result -> uri = result }
    Scaffold(topBar = {
        TopAppBar(title = { Text("MiMo 语音") }, navigationIcon = {
            IconButton(enabled = !busy, onClick = { TouchHaptics.click(view); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("保存参考录音后即可复刻朗读，无需等待训练。", style = MaterialTheme.typography.bodyMedium)
            if (available.isEmpty()) Text("请先在提供商设置中配置并启用 MiMo API Key。")
            Box {
                TextButton(enabled = !busy && available.isNotEmpty(), onClick = { TouchHaptics.click(view); providerMenu = true }) {
                    Text(provider?.name ?: "选择 MiMo 提供商")
                }
                io.github.mangi.eta.ui.components.EtaMaterialDropdownMenu(providerMenu, { providerMenu = false }) {
                    available.forEach { item ->
                        io.github.mangi.eta.ui.components.EtaMaterialDropdownMenuItem(item.name, onClick = {
                            TouchHaptics.click(view); selectedProvider = item.id; providerMenu = false
                        })
                    }
                }
            }
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("声音名称") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { TouchHaptics.click(view); picker.launch(arrayOf("audio/mpeg", "audio/wav", "audio/x-wav")) }) {
                Text(if (uri == null) "选择 MP3 / WAV 录音" else "录音已选择 · 重新选择")
            }
            Text("导入只保存到本机。试听或使用此声音朗读时，参考录音和文字会发送到所选 MiMo 提供商。Base64 编码后不超过 10 MB。", style = MaterialTheme.typography.bodySmall)
            Button(enabled = ready && !busy && uri != null && name.isNotBlank() && provider != null,
                modifier = Modifier.fillMaxWidth(), onClick = {
                    TouchHaptics.click(view)
                    val source = uri ?: return@Button
                    val target = provider ?: return@Button
                    val title = name
                    busy = true; notice = ""
                    scope.launch {
                        try {
                            MimoPersonalVoices.import(context, source, title, target.id)
                            name = ""; uri = null; notice = "参考录音已保存，可点击试听"
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            notice = if (e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty() else "录音导入失败，请检查文件格式与大小"
                        } finally { busy = false }
                    }
                }) { Text(if (busy) "正在保存…" else "保存参考声音") }
            OutlinedTextField(sample, { sample = it.take(300) }, label = { Text("试听文字") }, modifier = Modifier.fillMaxWidth())
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodyMedium)
            playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            voices.forEach { voice ->
                val owner = "mimo-preview:${voice.id}"
                val playing = playback.owner == owner
                val bound = available.firstOrNull { it.id == voice.providerId }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(voice.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${bound?.name ?: "提供商不可用"} · ${voice.durationMs / 1000.0} 秒 · 参考录音已保存", style = MaterialTheme.typography.bodySmall)
                        if (playing) Text(if (playback.preparing) "正在生成试听…" else "正在试听", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = playing || (bound != null && sample.isNotBlank() && !playback.recording), onClick = {
                                TouchHaptics.click(view); SpeechPlayback.previewMimo(context, voice, sample)
                            }) { Text(if (playing) "停止" else "试听") }
                            TextButton(enabled = bound != null, onClick = {
                                TouchHaptics.click(view)
                                val model = bound?.let { SpeechSynthesisModels.mergeCatalog(it) }?.firstOrNull { it.modelId == "mimo-v2.5-tts" && it.isEnabled }
                                if (model == null) notice = "请先启用 MiMo 朗读模型" else {
                                    SpeechPlayback.stop()
                                    ReadAloudVoiceHistory.rememberCurrent(context)
                                    Prefs.putString(Prefs.Keys.AGENT_TTS_MODE, "cloud")
                                    Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID, voice.providerId)
                                    Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_ID, model.id)
                                    Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, voice.id)
                                    ReadAloudVoiceHistory.remember(context, voice.providerId, model.id, voice.id)
                                    notice = "已将“${voice.name}”设为朗读声音"
                                }
                            }) { Text("用于朗读") }
                            TextButton(enabled = !busy, onClick = { TouchHaptics.click(view); deleting = voice }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { voice ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除“${voice.name}”？") },
            text = { Text("删除本机参考录音及记录。若正在用于朗读，删除后需要重新选择声音。") },
            confirmButton = { TextButton(onClick = {
                TouchHaptics.click(view); SpeechPlayback.stop(); deleting = null; busy = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { MimoPersonalVoices.remove(voice.id) }; notice = "已删除" }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; notice = "删除失败，请重试" }
                    finally { busy = false }
                }
            }) { Text("删除") } }, dismissButton = { TextButton(onClick = { TouchHaptics.click(view); deleting = null }) { Text("取消") } })
    }
}
