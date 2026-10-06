"""Source wiring checks only; does not compile or execute Android/Kotlin code."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class ContextDualMeterContractTest(unittest.TestCase):
    def text(self, name):
        return (ROOT / name).read_text()

    def test_runtime_decision_budget_is_not_an_ordinary_ring_event(self):
        budget = self.text('agent/model/AgentSilentContextBudget.kt')
        self.assertNotIn('onEvent', budget)
        self.assertIn('input.toLong() + currentLocal - measuredLocal', budget)
        loop = self.text('agent/model/AgentLoop.kt')
        self.assertIn('silentBudget.tokens(localRequestTokens())', loop)
        # Only a true summary replacement resets calibration; tool pruning preserves its anchor.
        self.assertEqual(1, loop.count('silentBudget.contextReplaced()'))
        self.assertLess(loop.index('silentBudget.requestStarted(requestLocal)'), loop.index('modelRetry.complete('))
        self.assertIn('requestBudget.consumeLocalBoundary()', loop)
        self.assertIn('ProviderEvent.RequestStarted) lastUsage = null', loop)

    def test_circle_and_send_guard_have_distinct_inputs(self):
        model = self.text('ui/model/AgentModelPickerUiState.kt')
        # A validated cloud receipt is shown as-is. The denominator prefers the window the
        # in-flight run was launched with, so a mid-run limit change cannot restate it.
        self.assertIn('return AgentContextUsageUi(billedContextTokens, window)', model)
        self.assertIn("val window = activeRunContextWindow?.takeIf { it > 0 } ?: selectedModel?.contextWindow", model)
        self.assertIn('internal fun compressionContextUsage(', model)
        bar = self.text('ui/components/AgentChatInputBar.kt')
        self.assertIn('historyTokenCount = historyTokenCount', bar)
        self.assertIn('contextSendBlocked(measuredContextTokens, autoCompressEnabled)', bar)
        gate = (ROOT / 'ui/components/ContextSendBudgetGate.kt').read_text()
        self.assertIn('shouldBlockSendForContextWindow(autoCompressEnabled, budget())', gate)
        app = self.text('ui/app/AgentAppState.kt')
        # Send guard, pre-send tail scaling and post-run tail scaling. Automatic compaction
        # itself reads the ring's cloud receipt, not this silent budget.
        # Includes the two local-tail counterparts; nullable run overhead gates pre-send sizing.
        self.assertEqual(5, app.count('compressionContextUsage('))
        self.assertIn('val estimatedTokens = runOverhead?.let { overhead ->', app)
        # Every automatic-compaction call passes the ring's cloud receipt, never a local estimate.
        self.assertEqual(4, app.count('shouldAutoCompress('))  # one declaration + three call sites
        self.assertIn('estimatedTokens = if (history == state.history) billedPromptTokens(state) else null', app)
        self.assertIn('if (!shouldAutoCompress(state.history, contextWindow, billedPromptTokens(state))) return', app)
        self.assertRegex(app, r'shouldAutoCompress\(\s*history,\s*config\.contextWindow,\s*(//[^\n]*\n\s*)?billedForCompression,')
        self.assertIn('if (projected) return', app)  # Raw projections cannot masquerade as learned UI estimates.
        silent = model.split('internal fun compressionContextUsage(', 1)[1].split('private fun draftContextTokens(', 1)[0]
        self.assertNotIn('liveContextUsage(', silent)
        self.assertNotIn('overheadCalibrationTokens', silent)
        self.assertIn('requestOverheadTokens.coerceAtLeast(0) + draft', silent)
        # Only a plausible receipt may become occupancy, judged against the run's own window.
        self.assertIn('CloudReceiptPlausibility.isOccupancy(', app)
        self.assertIn('runContextWindows[runId] ?: conversation?.let(::boundCompressionWindow)', app)

    def test_local_growth_cannot_reject_a_new_cloud_receipt(self):
        policy = self.text('ui/model/CloudReceiptPlausibility.kt')
        for removed in ('fitsGrowth', 'previousTokens', 'previousLocalTokens', 'localTokens'):
            self.assertNotIn(removed, policy)
        self.assertIn('return fitsWindow(value, contextWindow)', policy)
        app = self.text('ui/app/AgentAppState.kt')
        receipt = app.split('val measured = occupancy.takeIf {', 1)[1].split('if (measured != null)', 1)[0]
        self.assertIn('tokens = it, contextWindow = window', receipt)
        self.assertNotIn('localBasis', receipt)
        self.assertNotIn('billedPromptTokens', receipt)
        self.assertIn('historyTokens = event.requestHistoryTokens, overheadTokens = event.requestOverheadTokens', app)

    def test_request_calibration_is_optional_on_the_wire(self):
        wire = self.text('agent/runtime/AgentRuntimeWire.kt')
        for key in ('request_history_tokens', 'request_overhead_tokens'):
            self.assertIn('putInt("' + key + '"', wire)
            self.assertIn('optionalInt("' + key + '")', wire)

    def test_cloud_receipt_is_scoped_and_projection_is_not_persisted_as_cloud(self):
        store = self.text('ui/app/AgentConversationStore.kt')
        self.assertIn('state.livePromptTokens.takeUnless { state.livePromptIsProjected }', store)
        self.assertNotIn('?: io.github.mangi.eta.ui.model.latestBilledContextTokens(messages)', store)
        self.assertIn('livePromptTokens = receipt?.inputTokens', store)
        codec = self.text('ui/model/CloudUsageReceiptCodec.kt')
        for field in ('conversation', 'provider', 'model', 'history'):
            self.assertIn('receipt.optString("' + field + '") !=', codec)

if __name__ == '__main__':
    unittest.main()
