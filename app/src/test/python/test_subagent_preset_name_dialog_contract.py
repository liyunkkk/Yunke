"""Preset name form wiring guards; Compose/Robolectric CI is still required for layout validation."""
from pathlib import Path
import unittest

SRC = Path(__file__).resolve().parents[2]
MAIN = SRC / 'main/kotlin/io/github/mangi/eta'
TEST = SRC / 'test/kotlin/io/github/mangi/eta'


class SubAgentPresetNameDialogContractTest(unittest.TestCase):
    def test_real_name_form_uses_existing_window_host_and_preserves_input_and_actions(self):
        form = (MAIN / 'ui/components/SubAgentPresetNameDialog.kt').read_text()
        self.assertIn('import top.yukonga.miuix.kmp.window.WindowDialog', form)
        for needle in ('WindowDialog(', 'show = true', 'Column(Modifier.fillMaxWidth())',
                       'EtaFormTextField(', 'value = name', 'onNameChange(it.take(80))',
                       'hint = "组名称"', 'singleLine = true', 'onDismissRequest = onDismiss',
                       'TextButton(onClick = onDismiss) { Text("取消") }',
                       'TextButton(enabled = saveEnabled, onClick = onSave) { Text("保存") }'):
            self.assertIn(needle, form)
        self.assertNotIn('import androidx.compose.material3.AlertDialog', form)
        self.assertNotIn('OutlinedTextField(', form)  # Retain the shared production input styling.
        self.assertNotIn('BasicTextField(', form)
        self.assertNotIn('weight(', form)

    def test_catalog_still_performs_named_real_repository_writes_only_on_save(self):
        screen = (MAIN / 'ui/SubAgentSettingsScreen.kt').read_text()
        shell = screen.split('private fun SubAgentPresetDetail(', 1)[0]
        dialog = shell.split('if (add || rename != null) SubAgentPresetNameDialog(', 1)[1]
        dialog = dialog.split('delete?.let', 1)[0]
        before_save, save = dialog.split('onSave = {', 1)
        self.assertIn('onNameChange = { name = it }', before_save)
        self.assertIn('saveEnabled = editable && name.trim().isNotBlank()', before_save)
        self.assertIn('onDismiss = { add = false; rename = null }', before_save)
        self.assertNotIn('repository.', before_save)
        self.assertIn('if (directory.change {', save)
        self.assertIn('if (add) repository.addPreset(name.trim())', save)
        self.assertIn('repository.renamePreset(requireNotNull(rename).id, name.trim())', save)
        self.assertIn('}) { add = false; rename = null }', save)
        self.assertNotIn('owner', save)  # No writes to the live conversation owner.

    def test_regression_keeps_default_sync_and_checks_before_focus_then_real_input(self):
        tests = (TEST / 'ui/components/SubAgentPresetSettingsTest.kt').read_text()
        scenario = tests.split('fun addNamedEmptyGroupRenameAndDeleteDoNotChangeConversation()', 1)[1]
        for needle in ('compose.mainClock.autoAdvance', 'onNodeWithContentDescription("组名称")',
                       'nameField.assertIsDisplayed().assertIsNotFocused()',
                       'nameField.performTextInput(', 'nameField.performTextReplacement(',
                       'onNodeWithText("取消").performClick()', 'onNodeWithText("保存")',
                       'onNodeWithText("删除", substring = false).performClick()',
                       'assertTrue(created.config.profiles.isEmpty())',
                       'assertEquals("新组", fixture.repository.presets().single { it.id == createdId }.name)',
                       'assertEquals("改名组", renamed.name)', 'assertEquals(before, fixture.snapshot())'):
            self.assertIn(needle, scenario)
        self.assertLess(scenario.index('nameField.assertIsDisplayed().assertIsNotFocused()'),
                        scenario.index('nameField.performTextInput('))
        for forbidden in ('autoAdvance =', 'advanceTimeBy', 'runWithoutImplicitWait',
                          'invokeOnClick', 'SemanticsActions.SetText', '@Ignore', 'Thread.sleep'):
            self.assertNotIn(forbidden, tests)


if __name__ == '__main__':
    unittest.main()
