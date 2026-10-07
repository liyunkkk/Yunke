from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/ui'

class SettingsJankHotPathContract(unittest.TestCase):
    def test_live_run_does_not_resolve_every_body_owner_before_notice_check(self):
        source = (ROOT / 'model/AgentTerminalMessageOrder.kt').read_text()
        self.assertLess(source.index('if (runs.isEmpty()) return messages'), source.index('val messageOwners = messages.map'))
        self.assertIn('if (message !is SystemNoticeMessageUi || !message.code.isTerminal()) return@forEachIndexed', source)

    def test_usage_replaces_one_immutable_slot_and_preserves_holder_fallback(self):
        source = (ROOT / 'app/AgentAppState.kt').read_text().split('private fun updateAssistantUsage(', 1)[1].split('private fun revokeContextActual', 1)[0]
        self.assertIn('if (usage.isEmpty) return', source)
        self.assertIn('if (isStaleUsageAfterCompact(runId, round)) return', source)
        self.assertIn('if (targetIndex < 0)', source)
        self.assertIn('messages + AgentMessageUi(', source)
        self.assertIn('messages.incrementalSnapshot().replacing(targetIndex, target.copy(usage = usage))', source)
        self.assertNotIn('mapIndexed', source)

    def test_measurement_slots_are_opt_in_without_changing_effect_chain(self):
        settings = (ROOT / 'SettingsScreen.kt').read_text()
        scaffold = (ROOT / 'components/MiuixScaffoldPage.kt').read_text().split('fun MiuixScaffold(', 1)[0]
        labels = (ROOT / 'components/BoundedStreamDiagnostics.kt').read_text()
        for name, stage in [('topBarModifier', 'settings.topbar.measure'), ('listModifier', 'settings.lazy.measure')]:
            self.assertIn(f'{name}: Modifier = Modifier', scaffold)
            self.assertIn(f'{name} = Modifier.streamDiagnosticMeasure("{stage}")', settings)
            self.assertIn(f'"{stage}"', labels)
        self.assertIn('modifier = topBarModifier', scaffold)
        self.assertIn('modifier = listModifier', scaffold)
        chain = ['.fillMaxSize()', '.horizontalCutoutPadding()', '.captureForTopBar(backdrop)', '.scrollEndHaptic()', '.overScrollVertical()', '.nestedScroll(scrollBehavior.nestedScrollConnection)']
        lazy = scaffold.split('LazyColumn(', 1)[1]
        self.assertEqual(sorted(lazy.index(item) for item in chain), [lazy.index(item) for item in chain])
