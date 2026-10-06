"""Source guards only; no Kotlin build, pixel capture or Android UI execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
UI = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui'


class ModelPickerSelectionInsetContractTest(unittest.TestCase):
    def setUp(self):
        self.source = (UI / 'TtsModelPickerDialog.kt').read_text(encoding='utf-8')

    def test_only_selected_background_is_inset_by_one_dp(self):
        drawing = self.source.split('private fun Modifier.modelSelectionBackground', 1)[1].split('internal data class SpeechVoiceSections', 1)[0]
        self.assertIn('if (!selected) this else drawBehind', drawing)
        self.assertIn('val inset = 1.dp.toPx()', drawing)
        self.assertIn('topLeft = Offset(0f, inset)', drawing)
        self.assertIn('Size(size.width, (size.height - 2 * inset).coerceAtLeast(0f))', drawing)
        self.assertIn('val radius = 10.dp.toPx()', drawing)
        for layout in ('.padding(', '.height(', '.heightIn(', '.offset(', '.selectable(', '.clickable('):
            self.assertNotIn(layout, drawing)

    def test_model_and_clear_rows_keep_full_height_and_use_same_paint(self):
        dialog = self.source.split('internal fun TtsModelPickerDialog', 1)[1].split('private fun Modifier.modelSelectionBackground', 1)[0]
        row = 'Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))'
        self.assertEqual(2, dialog.count(row))
        self.assertIn('.modelSelectionBackground(highlightSelection && selected, MaterialTheme.colorScheme.surfaceVariant)', dialog)
        self.assertIn('.modelSelectionBackground(highlightSelection && state.selectedModel == null, MaterialTheme.colorScheme.surfaceVariant)', dialog)
        self.assertEqual(2, dialog.count('}).padding(horizontal = 4.dp, vertical = 6.dp)'))
        self.assertIn('onClearSelection()', dialog)
        self.assertIn('onModelSelected(model.providerId, model.id)', dialog)
        self.assertIn('indication = null', dialog)
        self.assertIn('if (index > 0) HorizontalDivider', dialog)

    def test_actual_subagent_entry_enables_highlight(self):
        entry = (UI / 'components/SubAgentProfileRow.kt').read_text(encoding='utf-8')
        picker = entry.split('if (usable && modelPicker)', 1)[1].split('if (usable && thinkingPicker', 1)[0]
        self.assertIn('TtsModelPickerDialog(models', picker)
        self.assertIn('highlightSelection = true', picker)


if __name__ == '__main__':
    unittest.main()
