package io.github.mangi.eta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.ui.components.WithoutPressRipple
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
    title: String,
    onClearSelection: (() -> Unit)? = null,
    highlightSelection: Boolean = false,
) {
    if (!show) return
    val view = LocalView.current
    var expanded by remember { mutableStateOf(state.selectedModel?.providerId ?: state.providerGroups.singleOrNull()?.providerId) }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        tonalElevation = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                if (onClearSelection != null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (highlightSelection && state.selectedModel == null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .selectable(selected = state.selectedModel == null, role = Role.RadioButton,
                                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                                TouchHaptics.click(view)
                                onClearSelection()
                            }).padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!highlightSelection) RadioButton(selected = state.selectedModel == null, onClick = null)
                        Text("无", modifier = Modifier.weight(1f).padding(start = 12.dp),
                            color = if (highlightSelection && state.selectedModel == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (state.providerGroups.isEmpty()) Text(stringResource(R.string.provider_empty))
                state.providerGroups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val isExpanded = expanded == group.providerId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                                TouchHaptics.click(view)
                                expanded = if (isExpanded) null else group.providerId
                            }.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group.providerName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (isExpanded) "收起" else "展开")
                    }
                    if (isExpanded) Column(Modifier.selectableGroup()) {
                        group.models.forEach { model ->
                            val selected = state.selectedModel?.providerId == model.providerId && state.selectedModel?.id == model.id
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (highlightSelection && selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                    .selectable(selected = selected, role = Role.RadioButton,
                                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {
                                        TouchHaptics.click(view)
                                        onModelSelected(model.providerId, model.id)
                                    }).padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!highlightSelection) RadioButton(selected = selected, onClick = null)
                                Text(model.displayName, style = MaterialTheme.typography.bodyMedium,
                                    color = if (highlightSelection && selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
        },
    )
    }
    }
}

internal data class SpeechVoiceSections(
    val female: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val male: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val other: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val personal: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
)

internal fun groupedSpeechVoices(
    voices: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
): SpeechVoiceSections {
    val personal = voices.filter { it.personal }
    val publicVoices = voices.filter { !it.personal }
    return SpeechVoiceSections(
        female = publicVoices.filter { "_female_" in it.id },
        male = publicVoices.filter { "_male_" in it.id },
        other = publicVoices.filter { "_female_" !in it.id && "_male_" !in it.id },
        personal = personal,
    )
}

@Composable
internal fun TtsVoicePickerDialog(
    show: Boolean,
    voices: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
    title: String,
) {
    if (!show) return
    val view = LocalView.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        tonalElevation = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                val sections = groupedSpeechVoices(voices)
                if (sections.female.isNotEmpty()) {
                    VoiceSectionTitle(stringResource(R.string.tts_voice_female))
                    sections.female.forEach { VoiceRow(it, selectedId, onSelected) }
                }
                if (sections.male.isNotEmpty()) {
                    VoiceSectionTitle(stringResource(R.string.tts_voice_male))
                    sections.male.forEach { VoiceRow(it, selectedId, onSelected) }
                }
                sections.other.forEach { VoiceRow(it, selectedId, onSelected) }
                if (sections.personal.isNotEmpty()) {
                    VoiceSectionTitle(stringResource(R.string.tts_voice_personal))
                    sections.personal.forEach { VoiceRow(it, selectedId, onSelected) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
internal fun SpeechRadioPickerDialog(
    show: Boolean,
    title: String,
    rows: List<Pair<String, String>>,
    selectedId: String,
    emptyText: String = "",
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    if (!show) return
    val view = LocalView.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        tonalElevation = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                if (rows.isEmpty()) Text(emptyText)
                rows.forEach { (id, label) ->
                    val selected = id == selectedId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                TouchHaptics.click(view)
                                onSelected(id)
                            }).padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 12.dp))
                    }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun VoiceRow(
    voice: io.github.mangi.eta.agent.voice.tts.SpeechVoice,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    val view = LocalView.current
    val selected = voice.id == selectedId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = {
                TouchHaptics.click(view)
                onSelected(voice.id)
            })
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = voice.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}
