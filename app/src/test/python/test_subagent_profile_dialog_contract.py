"""Source boundary contracts only; Compose/runtime tests remain necessary."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentProfileDialogContractTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_draft_form_is_not_an_immediate_editor_form(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for forbidden in ('LocalConversationSubAgentEditor', 'SubAgentProfileRow(',
                          '.updateProfile(', '.saveModel(', '.saveParallelLimit(', '.cycleGptSpeed(', '.add()', '.remove('):
            self.assertNotIn(forbidden, text)
        for required in ('editor.newProfileDraft()', 'editor.changeProfileModel(it, selection)',
                         'profile.withRole(requireNotNull(value))', 'AgentModelPickerProjector.project(',
                         'TtsModelPickerDialog(', 'ThinkingEffortPickerDialog(', 'EtaFormTextField(',
                         'if (draft.supportsTaskTier)', 'if (draft.role == "image_generation")'):
            self.assertIn(required, text)
        self.assertNotIn('OutlinedTextField(', text)

    def test_gpt_speed_edit_is_local_and_keyed_by_current_provider_selection(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('providers.singleOrNull { it.id == draft.providerId && it.isEnabled }',
                         'speedProvider?.models?.singleOrNull { it.id == draft.modelId && it.isEnabled }',
                         'val canEditSpeed = boundConfig != null && supportsGptSpeedBinding(speedProvider, speedModel)',
                         'if (canEditSpeed) SubAgentSettingRow("GPT 速度"',
                         'gptSpeedDisplayName(draft.gptSpeedForModel())'):
            self.assertIn(required, text)
        edit = text.split('if (canEditSpeed) SubAgentSettingRow(', 1)[1].split('SubAgentSettingRow("职责"', 1)[0]
        for required in ('interact {', 'session.edit { profile ->',
                         'profile.providerId != draft.providerId', 'profile.modelId != draft.modelId',
                         'profile.role != draft.role',
                         'profile.copy(gptSpeedByModel = profile.gptSpeedByModel +',
                         'SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId)',
                         'profile.gptSpeedForModel().next()'):
            self.assertIn(required, edit)
        for forbidden in ('editor.', 'boundConfig.model', 'reasoning =', 'tier =', 'Toast', 'scope.launch'):
            self.assertNotIn(forbidden, edit)

    def test_user_dismiss_cannot_abandon_submitting_but_saved_and_owner_invalidation_close(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        self.assertIn('val dismiss = { session.dismiss(); latestDismiss() }', text)
        self.assertIn('val userDismiss = { if (!session.submitting) dismiss() }', text)
        self.assertIn('onDismissRequest = userDismiss', text)
        self.assertIn('SubAgentDraftDialogActions(ready, !session.submitting, userDismiss, onConfirm =', text)
        saved = text.split('if (result is ConversationSubAgentPreferences.WriteResult.Saved &&', 1)[1].split('else if', 1)[0]
        self.assertIn('session.isCurrent(latestEditor, latestEnabled)', saved)
        self.assertIn('dismiss()', saved)
        self.assertNotIn('userDismiss()', saved)
        self.assertNotIn('!session.submitting', saved)
        invalidated = text.split('LaunchedEffect(session, current)', 1)[1].split('if (!current) return', 1)[0]
        self.assertIn('if (!current) { session.dismiss(); latestDismiss() }', invalidated)
        self.assertNotIn('!session.submitting', invalidated)

    def test_every_open_is_owner_application_and_snapshot_bound(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('val openToken = Any()', 'editor.isDisposed', 'currentEditor !== editor',
                         'currentEditor?.owner != owner', 'current.presetApplicationToken == expectedApplicationToken',
                         'config.detached()', 'expectedProfile ?: editor.newProfileDraft()',
                         'DisposableEffect(session)', 'onDispose { session.dismiss() }',
                         'session.canCommit(latestEditor, latestEnabled, capturedTicket)'):
            self.assertIn(required, text)
        dismiss = text.split('fun dismiss()', 1)[1].split('fun isCurrent', 1)[0]
        self.assertNotIn('repository', dismiss)

    def test_suspend_submission_captures_and_only_saved_closes_and_haptics(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        submission = text.split('// Snapshot every argument before suspension;', 1)[1].split('if (usable && modelPicker)', 1)[0]
        for required in ('val capturedDraft', 'val capturedTicket', 'val capturedExpected',
                         'val capturedApplication', 'val capturedParallel', 'session.submitting = true',
                         'scope.launch', 'commitProfileDraft(', 'WriteResult.Saved',
                         '草稿已保留', 'finally { session.submitting = false }'):
            self.assertIn(required, submission)
        self.assertLess(submission.index('val capturedDraft'), submission.index('scope.launch'))
        self.assertLess(submission.index('WriteResult.Saved'), submission.index('TouchHaptics.click(view)'))
        actions = text.split('private fun SubAgentDraftDialogActions(', 1)[1]
        self.assertIn('Arrangement.spacedBy(8.dp)', actions)
        self.assertEqual(actions.count('Modifier.weight(1f)'), 2)
        self.assertIn('textButtonColorsPrimary()', actions)
        self.assertIn('onClick = onConfirm', actions)  # no eager confirmation haptic

    def test_parallel_uses_owner_actual_or_pending_confirmed_memory_and_api_pool(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('SubAgentParallelModel(it.providerId, it.model)',
                         'expectedProfile != null && !bindingChanged',
                         'if (next != null && !originalBinding) editor.rememberedParallelLimit(draft)',
                         'parallelEdited = restored != null',
                         'restored ?: openedConfig.parallelLimit(next)',
                         'SubAgentParallelLimitChange(model, number, openedConfig.parallelLimit(model))',
                         'if (!parallelEdited) return null', 'bindingChanged = true', 'resetParallel()'):
            self.assertIn(required, text)
        self.assertNotIn('SubAgentParallelModel(draft.providerId, draft.modelId)', text)

    def test_entry_points_remove_tier_shortcuts_and_never_preadd(self):
        row = self.source('ui/components/SubAgentProfileRow.kt')
        self.assertNotIn('SubAgentTaskTierButton', row)
        self.assertNotIn('"任务分工"', row)
        self.assertIn('Icon(Icons.Rounded.MoreVert, "配置${profile.name}"', row)
        self.assertIn('SubAgentProfileDraftSession.open(editor, profile)', row)
        self.assertIn('onLongClickLabel = "调整${profile.name}思考深度"', row)
        conversation = self.source('ui/components/ConversationCollaborationDialog.kt')
        hint = conversation.split('Text("点按模型切换 · 长按调整思考"', 1)[1]
        self.assertIn('Icon(Icons.Rounded.Add, "添加子代理")', hint)
        self.assertIn('SubAgentProfileDraftSession.open(panelEditor)', hint)
        self.assertIn('Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),\n                                            verticalAlignment = Alignment.CenterVertically)', conversation)
        settings = self.source('ui/SubAgentSettingsScreen.kt')
        self.assertNotIn('editor.add()', settings)
        self.assertIn('SubAgentProfileDraftSession.open(editor)', settings)
        self.assertIn('Text("重命名")', settings)
        self.assertIn('editor.remove(profile.id)', settings)

    def test_window_dialog_works_without_scaffold_and_scrolls_body_not_actions(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        self.assertIn('import top.yukonga.miuix.kmp.window.WindowDialog', text)
        self.assertEqual(text.count('WindowDialog(show = true'), 2)  # form + nested choices
        self.assertNotIn('OverlayDialog(', text)
        self.assertNotIn('renderInRootScaffold', text)
        for required in ('profileDialogScrollableBody().verticalScroll(',
                         'SubAgentDraftDialogActions(ready', 'LocalRippleConfiguration provides null', 'WithoutPressRipple'):
            self.assertIn(required, text)

    def test_session_shortcuts_reserve_real_48dp_layout_targets(self):
        row = self.source('ui/components/SubAgentProfileRow.kt')
        self.assertIn('IconButton(modifier = Modifier.size(48.dp), enabled = usable', row)
        dialog = self.source('ui/components/ConversationCollaborationDialog.kt')
        self.assertIn('IconButton(modifier = Modifier.size(48.dp), enabled = panelEnabled', dialog)

    def test_ui_fixture_uses_production_theme_without_overlay_host_workaround(self):
        fixture = (Path(__file__).resolve().parents[1] / 'kotlin/io/github/mangi/eta/ui/components/SubAgentUiFixture.kt').read_text()
        self.assertIn('AgentAppTheme(', fixture)
        self.assertIn('applyInterfaceScale = true', fixture)
        self.assertNotIn('Scaffold(', fixture)
        self.assertNotIn('OverlayHost(', fixture)


if __name__ == '__main__':
    unittest.main()
