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
                          '.updateProfile(', '.saveModel(', '.saveParallelLimit(', '.add()', '.remove('):
            self.assertNotIn(forbidden, text)
        for required in ('editor.newProfileDraft()', 'editor.changeProfileModel(it, selection)',
                         'profile.withRole(requireNotNull(value))', 'AgentModelPickerProjector.project(',
                         'TtsModelPickerDialog(', 'ThinkingEffortPickerDialog(', 'EtaFormTextField(',
                         'if (draft.supportsTaskTier)', 'if (draft.role == "image_generation")'):
            self.assertIn(required, text)
        self.assertNotIn('OutlinedTextField(', text)

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

    def test_overlay_scrolls_body_not_actions_and_removes_ripple(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('OverlayDialog(show = true', 'profileDialogScrollableBody().verticalScroll(',
                         'SubAgentDraftDialogActions(ready', 'LocalRippleConfiguration provides null', 'WithoutPressRipple'):
            self.assertIn(required, text)


if __name__ == '__main__':
    unittest.main()
