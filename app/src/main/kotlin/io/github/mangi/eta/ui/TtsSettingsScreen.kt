package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
    val engine = remember(selectedProvider, modelId) { SpeechEngineResolver.resolve(selectedProvider, modelId) }
    val mimoVoices by io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.state.collectAsState()
    LaunchedEffect(Unit) { runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.mangi.eta.agent.voice.mimo.MimoPersonalVoices.load(context) } } }
    val personalVoices by io.github.mangi.eta.agent.voice.doubao.PersonalVoices.state.collectAsState()
    LaunchedEffect(Unit) { io.github.mangi.eta.agent.voice.doubao.PersonalVoices.load(context) }
    val catalog = remember(engine, modelId, personalVoices, mimoVoices, selectedProvider) {
        SpeechVoices.catalog(engine, modelId) + if (engine == io.github.mangi.eta.agent.voice.tts.SpeechEngine.DOUBAO && !io.github.mangi.eta.agent.voice.tts.DoubaoSpeech.usesCreate(modelId)) {
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

@Composable
private fun TtsVoicePickerDialog(
    show: Boolean,
    voices: List<SpeechVoice>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    if (!show) return
    val view = LocalView.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tts_voice)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                val personal = voices.filter { it.personal }
                val female = voices.filter { !it.personal && "_female_" in it.id }
                val male = voices.filter { !it.personal && "_male_" in it.id }
                val other = voices.filter { !it.personal && "_female_" !in it.id && "_male_" !in it.id }
                if (female.isNotEmpty()) {
                    VoiceSectionTitle(stringResource(R.string.tts_voice_female))
                    female.forEach { VoiceRow(it, selectedId, onSelected) }
                }
                if (male.isNotEmpty()) {
                    VoiceSectionTitle(stringResource(R.string.tts_voice_male))
                    male.forEach { VoiceRow(it, selectedId, onSelected) }
                }
                other.forEach { VoiceRow(it, selectedId, onSelected) }
                if (personal.isNotEmpty()) {
                    VoiceSectionTitle(stringResource(R.string.tts_voice_personal))
                    personal.forEach { VoiceRow(it, selectedId, onSelected) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun VoiceSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun VoiceRow(voice: SpeechVoice, selectedId: String, onSelected: (String) -> Unit) {
    val view = LocalView.current
    val selected = voice.id == selectedId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = { TouchHaptics.click(view); onSelected(voice.id) })
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        // Public and personal voices share the same typography, including the selected row.
        Text(
            text = voice.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Normal,
            fontStyle = FontStyle.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}
