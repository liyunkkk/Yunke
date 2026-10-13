from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/ui'


class EditorSpeedDisplayContractTest(unittest.TestCase):
    def test_only_persona_long_editors_opt_into_scroll_isolation(self):
        page = (ROOT / 'screens/assistants/AssistantEditScreen.kt').read_text()
        self.assertEqual(page.count('.isolateEditorScroll(editorScrollIsolation)'), 2)
        self.assertIn('editorScrollIsolation = editorScrollIsolation', page)
        scaffold = (ROOT / 'components/MiuixScaffoldPage.kt').read_text()
        self.assertIn('editorScrollIsolation: EditorScrollIsolation? = null', scaffold)
        self.assertNotIn('userScrollEnabled = !editorOwnsGesture', scaffold)
        isolation = (ROOT / 'components/EditorScrollIsolation.kt').read_text()
        self.assertIn('awaitPointerEvent(PointerEventPass.Main)', isolation)
        self.assertIn('change.consume()', isolation)
        self.assertIn('if (editorOwnsGesture) Modifier else Modifier.scrollEndHaptic().overScrollVertical()', scaffold)
        self.assertIn('.nestedScroll(pageScrollConnection)', scaffold)

    def test_disabled_speed_tint_shared_without_enabling_controls(self):
        bar = (ROOT / 'components/AgentChatInputBar.kt').read_text()
        chip = bar.split('internal fun ThinkingEffortChip(', 1)[1].split('internal fun ThinkingEffortPickerDialog(', 1)[0]
        self.assertIn('tint = gptSpeedChipTint(', chip)
        self.assertIn('val canLongPressSpeed = gptSpeedSupported && enabled', chip)
        self.assertIn('if (gptSpeedSupported) gptSpeedMode else GptSpeedMode.NORMAL', chip)
        child = (ROOT / 'components/SubAgentGptSpeedButton.kt').read_text()
        self.assertIn('gptSpeedChipTint(contentColor, mode, enabled = usable)', child)
        self.assertIn('enabled = usable', child)
