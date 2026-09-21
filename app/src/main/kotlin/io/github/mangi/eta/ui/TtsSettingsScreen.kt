package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.tts.SpeechEngineResolver
import io.github.mangi.eta.agent.voice.tts.ReadAloudVoiceHistory
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.SpeechVoice
import io.github.mangi.eta.agent.voice.tts.SpeechVoices
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun TtsSettingsScreen(onBack: () -> Unit) {
    var personalPage by remember { mutableStateOf(false) }
    if (personalPage) {
        PersonalVoicesScreen(onBack = { personalPage = false })
        return
    }
    val context = LocalContext.current
    val view = LocalView.current
    var cloud by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODE) == "cloud") }
    var providerId by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID)) }
    var modelId by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_ID)) }
    var voice by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_VOICE)) }
    var picker by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf(false) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val models = remember(providers, providerId, modelId) {
        AgentModelPickerProjector.project(providers.filter(SpeechSynthesisModels::isReadAloudProvider), providerId, modelId, includeSpeechModels = true, speechOnly = true)
    }
    val selectedProvider = remember(providers, providerId) { providers.firstOrNull { it.id == providerId } }
    val speechModelId = models.selectedModel?.modelId.orEmpty()
    val engine = remember(selectedProvider, speechModelId) { SpeechEngineResolver.resolve(selectedProvider, speechModelId) }
    val mimoVoices by io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.state.collectAsState()
    LaunchedEffect(Unit) { runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.load(context) } } }
    val personalVoices by io.github.mangi.eta.agent.voice.doubao.PersonalVoices.state.collectAsState()
    LaunchedEffect(Unit) { io.github.mangi.eta.agent.voice.doubao.PersonalVoices.load(context) }
    val catalog = remember(engine, speechModelId, personalVoices, mimoVoices, selectedProvider) {
        SpeechVoices.catalog(engine, speechModelId) + if (engine == io.github.mangi.eta.agent.voice.tts.SpeechEngine.DOUBAO && !io.github.mangi.eta.agent.voice.tts.DoubaoSpeech.usesCreate(speechModelId)) {
            personalVoices.filter { it.tts && it.accepted && it.account == io.github.mangi.eta.agent.voice.doubao.PersonalVoices.account(selectedProvider?.apiKey.orEmpty()) }
                .map { SpeechVoice(it.id, it.name, personal = true) }
        } else if (engine == io.github.mangi.eta.agent.voice.tts.SpeechEngine.MIMO) {
            mimoVoices.filter { it.providerId == selectedProvider?.id }.map { SpeechVoice(it.id, it.name, personal = true) }
        } else emptyList()
    }
    LaunchedEffect(Unit) { ReadAloudVoiceHistory.rememberCurrent(context) }
    val playback by SpeechPlayback.state.collectAsState()
    val sample = stringResource(R.string.tts_sample)
    LaunchedEffect(cloud, providerId, selectedProvider?.id, engine, modelId, catalog, voice) {
        // A missing/expired personal voice must not silently become a public voice.
        if (voice.startsWith("etaClone") || voice.startsWith("S_") || voice.startsWith("mimo-local-")) return@LaunchedEffect
        if (!SpeechVoices.shouldReplaceStoredVoice(
                cloud = cloud,
                providerId = providerId,
                providerReady = selectedProvider != null,
                storedVoice = voice,
                catalogIds = catalog.map { it.id },
            )
        ) {
            return@LaunchedEffect
        }
        val fallback = catalog.first().id
        voice = fallback
        Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, fallback)
        ReadAloudVoiceHistory.remember(context, providerId, modelId, fallback)
    }
    MiuixScaffoldPage(title = stringResource(R.string.tts_title), onBack = onBack) {
        item(key = "my_voices") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(title = "我的声音", summary = "导入、制作和试听个人声音",
                    insideMargin = PaddingValues(16.dp), onClick = { TouchHaptics.click(view); personalPage = true })
            }
        }
        item(key = "tts_mode") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.tts_cloud),
                    summary = stringResource(R.string.tts_mode_hint),
                    checked = cloud,
                    insideMargin = PaddingValues(16.dp),
                    onCheckedChange = {
                        TouchHaptics.click(view)
                        SpeechPlayback.stop()
                        cloud = it
                        Prefs.putString(Prefs.Keys.AGENT_TTS_MODE, if (it) "cloud" else "system")
                    },
                )
            }
        }
        if (cloud) {
            item(key = "tts_model") {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.tts_model),
                        summary = models.selectedModel?.let { "${it.providerName} · ${it.displayName}" } ?: stringResource(R.string.tts_select_model),
                        insideMargin = PaddingValues(16.dp),
                        onClick = { TouchHaptics.click(view); picker = true },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.tts_voice),
                        summary = catalog.firstOrNull { it.id == voice }?.name
                            ?: voice.ifBlank { stringResource(R.string.tts_select_voice) },
                        insideMargin = PaddingValues(16.dp),
                        enabled = models.selectedModel != null && catalog.isNotEmpty(),
                        onClick = { TouchHaptics.click(view); voicePicker = true },
                    )
                }
            }
        }
        item(key = "tts_preview") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(if (playback.owner == "tts-preview") R.string.tts_stop else R.string.tts_preview),
                    summary = playback.error ?: playback.source.takeIf { it.isNotBlank() }
                        ?: stringResource(if (playback.preparing) R.string.tts_preparing else R.string.tts_manual_hint),
                    insideMargin = PaddingValues(16.dp),
                    enabled = !playback.recording && (!cloud || (models.selectedModel != null && voice.isNotBlank())),
                    onClick = { TouchHaptics.click(view); SpeechPlayback.toggle(context, "tts-preview", sample) },
                )
            }
        }
    }
    TtsModelPickerDialog(
        state = models, show = picker, onDismiss = { picker = false },
        title = stringResource(R.string.tts_model),
        onModelSelected = { provider, model ->
            SpeechPlayback.stop()
            if (providerId != provider || modelId != model) {
                ReadAloudVoiceHistory.remember(context, providerId, modelId, voice)
                voice = ReadAloudVoiceHistory.restore(context, provider, model)
                Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, voice)
            }
            providerId = provider
            modelId = model
            Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID, provider)
            Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_ID, model)
            picker = false
        },
    )
    TtsVoicePickerDialog(
        show = voicePicker,
        voices = catalog,
        selectedId = voice,
        title = stringResource(R.string.tts_voice),
        onDismiss = { voicePicker = false },
        onSelected = { id ->
            SpeechPlayback.stop()
            voice = id
            Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, id)
            ReadAloudVoiceHistory.remember(context, providerId, modelId, id)
            voicePicker = false
        },
    )
}
