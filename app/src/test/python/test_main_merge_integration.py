"""Source integration guards, not a substitute for compiled Kotlin tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class MainMergeIntegrationTest(unittest.TestCase):
    def test_inline_controls_retain_authorized_preview_and_safe_close(self):
        page = (ROOT / 'ui/VirtualDisplayRecoveryScreen.kt').read_text()
        for text in ('VirtualDisplayRecoveryControls(', 'VirtualDisplayWebPreview.openWithManualClose(context)',
                     'onDispose {', 'VirtualDisplayWebPreview.stop()', 'snapshot.optBoolean("recoverable")'):
            self.assertIn(text, page)
        self.assertEqual(1, page.count('check(stillInstalled)'))
        self.assertEqual(3, page.count('Modifier.weight(1f).fillMaxHeight()'))
        self.assertNotIn('VirtualDisplayWebPreview.open(context)', page)
        self.assertNotIn('Scaffold(', page)

    def test_confirmed_empty_result_and_trailing_notice_paths_are_wired(self):
        live = (ROOT / 'ui/app/AgentAppState.kt').read_text()
        self.assertIn('result.ok && (result.content.isNotBlank() || VirtualCompletionNotice.confirmed(result))', live)
        recovery = (ROOT / 'ui/app/AgentPendingResultRecovery.kt').read_text()
        self.assertLess(recovery.index('VirtualCompletionNotice.confirmed(result) && partial != null'), recovery.index('result.ok -> SystemNoticeMessageUi('))
        body = (ROOT / 'ui/components/AgentChatBody.kt').read_text()
        self.assertIn('hasPendingAssistantReveal(currentVisibleMessages.value)', body)
        self.assertIn('retained != null && retained.revealedContent != message.content', body)

    def test_initial_budget_reaches_actual_compaction_gate(self):
        source = (ROOT / 'agent/model/AgentLoop.kt').read_text()
        gate = source.split('private fun applyCompaction(', 1)[1].split('val compressConfig', 1)[0]
        self.assertIn('decisionTokens < AgentContextCompactor.autoPressureTokens(window)', gate)
        self.assertNotIn('hardPressure && reportedRequestTokens()', gate)
        self.assertIn('requestBudget.contextReplaced()', source)

    def test_cloud_usage_survives_request_start_and_pending_pruning(self):
        source = (ROOT / 'ui/app/AgentAppState.kt').read_text()
        self.assertNotIn('resetLiveUsageForRequest(', source)
        self.assertNotIn('runCloudUsage', source)
        # Both automatic and pending/manual pruning preserve the last cloud reading.
        handler = source.split('private fun applyRuntimeCompactedHistory(', 1)[1]
        pruning = handler.split('if (event.pruningOnly)', 1)[1].split('runCompressedDuringRun.add(runId)', 1)[0]
        self.assertIn('current.copy(history = event.history)', pruning)
        self.assertNotIn('livePromptTokens = null', pruning)
        self.assertNotIn('cloudHistoryTokens = null', pruning)
        update = source.split('private fun updateLivePromptTokens(', 1)[1][:650]
        self.assertIn('stoppingRuns.containsKey(runId)', update)
        self.assertIn('runId in invalidatedUsageRuns', update)

if __name__ == '__main__':
    unittest.main()
