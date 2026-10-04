package io.github.mangi.eta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class VoiceSettingsSection(val title: Int) {
    RECOGNITION(R.string.voice_section_speech),
    READ_ALOUD(R.string.voice_section_tts),
    CONVERSATION(R.string.voice_section_conversation),
    REALTIME(R.string.voice_section_realtime),
    PERSONAL(R.string.voice_section_personal),
}

internal val LocalEmbeddedVoiceSettings = staticCompositionLocalOf { false }

/** Reuses section content without nesting a second page title/scaffold. */
@Composable
internal fun VoiceSettingsSectionPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    if (LocalEmbeddedVoiceSettings.current) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), content = content)
    } else {
        MiuixScaffoldPage(title = title, onBack = onBack, content = content)
    }
}

@Composable
internal fun VoiceSettingsScreen(
    onBack: () -> Unit,
    initialSection: VoiceSettingsSection = VoiceSettingsSection.RECOGNITION,
) {
    var selected by rememberSaveable { mutableStateOf(initialSection) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    MiuixScaffold(title = stringResource(R.string.voice_title), onBack = onBack) { padding, scrollBehavior, sidePadding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = sidePadding).nestedScroll(scrollBehavior.nestedScrollConnection)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    TouchHaptics.click(view); expanded = !expanded
                }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(selected.title), modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(R.string.voice_section_choose), tint = MiuixTheme.colorScheme.onSurface)
                }
                if (expanded) {
                    LazyColumn(Modifier.heightIn(max = 220.dp).padding(bottom = 8.dp)) {
                        items(VoiceSettingsSection.entries, key = { it.name }) { section ->
                            Text(stringResource(section.title), style = MiuixTheme.textStyles.body2,
                                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                                    TouchHaptics.click(view); selected = section; expanded = false
                                }.padding(horizontal = 12.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalEmbeddedVoiceSettings provides true) {
                    key(selected) {
                        when (selected) {
                            VoiceSettingsSection.RECOGNITION -> SpeechSettingsScreen(onBack)
                            VoiceSettingsSection.READ_ALOUD -> TtsSettingsScreen(onBack)
                            VoiceSettingsSection.CONVERSATION -> TtsSettingsScreen(onBack, conversation = true)
                            VoiceSettingsSection.REALTIME -> VoiceModeSettingsScreen(onBack)
                            VoiceSettingsSection.PERSONAL -> PersonalVoicesScreen(onBack)
                        }
                    }
                }
            }
        }
    }
    BackHandler(enabled = expanded) { expanded = false }
}
