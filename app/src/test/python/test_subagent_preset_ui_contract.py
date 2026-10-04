"""Preset UI/source contracts; these do not substitute for running Compose tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentPresetUiContractTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_settings_shell_owns_repository_and_preset_editor_not_conversation_local(self):
        text = self.source('ui/SubAgentSettingsScreen.kt')
        shell, detail = text.split('private fun SubAgentPresetDetail(', 1)
        self.assertNotIn('LocalConversationSubAgentEditor.current', text)
        for needle in ('ConversationSubAgentPreferences()', 'remember { repositoryFactory() }',
                       'SubAgentConfigKey.Preset(group.id)', 'selectedId == owner.value',
                       'repository.presetExists(owner.value)', 'LocalConversationSubAgentEditor provides editor',
                       'BackHandler(enabled = isCurrentRoute, onBack = detailBack)', 'editor.dispose(); selectedId = null',
                       'DisposableEffect(editor)', 'onDispose { editor.dispose() }', '添加子代理组'):
            self.assertIn(needle, shell)
        for needle in ('添加子代理', 'SubAgentProfileRow(', 'settings = true', 'setDiagnosticsEnabled',
                       'updateProfile(profile.id)', 'editor.remove(profile.id)', '自动委派'):
            self.assertIn(needle, detail)
        self.assertNotIn('taskRunning', shell)
        self.assertNotIn('editor.add()', detail)
        self.assertIn('SubAgentProfileDraftSession.open(editor)', detail)
        self.assertIn('SubAgentProfileConfigDialog(session, editor, providers', detail)

    def test_catalog_supports_named_empty_add_rename_delete_and_explicit_retry(self):
        settings = self.source('ui/SubAgentSettingsScreen.kt')
        for needle in ('repository.addPreset(name.trim())', 'repository.renamePreset(', 'repository.removePreset(',
                       'SubAgentPresetCard(', 'SubAgentPresetDirectoryStatus(directory)'):
            self.assertIn(needle, settings)
        cards = self.source('ui/components/SubAgentPresetCard.kt')
        for needle in ('repository.presets()', 'repository.presetsFlow().collect', 'recoverDurability()',
                       'if (error != null) return@LaunchedEffect', 'TouchHaptics.click(view); directory.retry()'):
            self.assertIn(needle, cards)

    def test_shared_card_is_light_rounded_haptic_and_ripple_free(self):
        cards = self.source('ui/components/SubAgentPresetCard.kt')
        for needle in ('RoundedCornerShape(24.dp)', 'primaryContainer.copy(alpha = 0.38f)',
                       'indication = null', 'TouchHaptics.click(view)', 'Role.Button'):
            self.assertIn(needle, cards)
        for path in ('ui/SubAgentSettingsScreen.kt', 'ui/components/ConversationCollaborationDialog.kt'):
            self.assertIn('WithoutPressRipple', self.source(path))
            self.assertIn('LocalRippleConfiguration provides null', self.source(path))

    def test_legacy_dialog_is_non_writing_and_saved_only_enters_original_panel(self):
        text = self.source('ui/components/ConversationCollaborationDialog.kt')
        for needle in ('current?.appliedPresetId == null', '使用当前配置', '切换子代理组',
                       'val editor = LocalConversationSubAgentEditor.current', 'editor.applyPreset(preset.id)',
                       'result is ConversationSubAgentPreferences.WriteResult.Saved',
                       '子代理组未应用，当前会话配置已保留', 'popup.isCurrent(ticket, popup)',
                       'DisposableEffect(popup)', 'popup.dismiss()'):
            self.assertIn(needle, text)
        self.assertNotIn('SubAgentConfigKey.Preset', text)
        self.assertNotIn('LaunchedEffect', text)  # Opening never auto-applies or replaces the old owner config.
        use_current = text.split('Text("使用当前配置")', 1)[0].rsplit('TextButton(enabled = canChange', 1)[1]
        self.assertNotIn('applyPreset', use_current)
        self.assertNotIn('setEnabled', use_current)

    def test_rows_capture_application_and_dispose_instead_of_trusting_root_state(self):
        dialog = self.source('ui/components/ConversationCollaborationDialog.kt')
        for needle in ('key(editor, current.presetApplicationToken)', 'editor.scoped(current.presetApplicationToken)',
                       'latestOwnerMatches() && !latestTaskRunning && !latestChoosing',
                       'LocalConversationSubAgentEditor provides panelEditor', 'onDispose { panelEditor.dispose() }'):
            self.assertIn(needle, dialog)
        editor = self.source('ui/components/ConversationSubAgentEditor.kt')
        for needle in ('fun dispose() { disposed = true }', '!disposed && state is SubAgentEditorState.Loaded',
                       'root.enabled && gate() && root.applicationMatches(applicationToken)',
                       'old.presetApplicationToken != observedToken',
                       'repository.applyPreset(owner, id) { enabled && canApply() }'):
            self.assertIn(needle, editor)
        speed = editor.split('suspend fun cycleGptSpeed(', 1)[1].split('fun saveModel(', 1)[0]
        self.assertLess(speed.index('val capturedToken = capturedConfig.presetApplicationToken'), speed.index('providerLookup('))
        self.assertIn('if (!applicationMatches(capturedToken)) throw LostOwner()', speed)

    def test_scoped_panel_observes_and_displays_explicit_error_recovery(self):
        dialog = self.source('ui/components/ConversationCollaborationDialog.kt')
        panel = dialog.split('val panelState = panelEditor.observe()', 1)[1]
        for needle in ('val panelConfig = (panelState as? SubAgentEditorState.Loaded)?.config',
                       'val panelEnabled = canChange && panelEditor.enabled && panelConfig != null',
                       'when (panelState)', '本会话配置保存或读取失败', '重试本会话配置',
                       'editor.retry()', 'panelEditor.retry()',
                       '(panelConfig ?: current).profiles.forEach',
                       'SubAgentProfileRow(profile, providers, enabled = panelEnabled,',
                       'SubAgentProfileDraftSession.open(panelEditor)',
                       'SubAgentProfileConfigDialog(session, panelEditor, providers',
                       'Switch(checked = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled'):
            self.assertIn(needle, panel)
        self.assertNotIn('SubAgentProfileRow(profile, providers, enabled = canChange)', panel)

    def test_back_is_gated_by_actual_route_and_presets_cannot_run(self):
        settings = self.source('ui/SubAgentSettingsScreen.kt')
        self.assertIn('isCurrentRoute: Boolean = true', settings)
        self.assertIn('BackHandler(enabled = isCurrentRoute, onBack = detailBack)', settings)
        root = self.source('ui/app/AgentAppRoot.kt')
        self.assertIn('isCurrentRoute = backStack.lastOrNull() == AppRoute.SubAgents', root)
        state = self.source('ui/app/AgentAppState.kt')
        self.assertIn('is SubAgentConfigKey.Preset -> error("预设不能作为会话运行配置")', state)

    def test_parallel_limit_wording_fits_preset_and_conversation_without_layout_change(self):
        text = self.source('ui/components/SubAgentParallelLimitRow.kt')
        self.assertIn('本配置内相同提供商/API 模型共用此上限', text)
        self.assertNotIn('同一会话下', text)


if __name__ == '__main__':
    unittest.main()
