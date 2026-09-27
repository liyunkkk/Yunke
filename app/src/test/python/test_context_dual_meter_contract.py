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
        self.assertIn('return AgentContextUsageUi(billedContextTokens, selectedModel?.contextWindow)', model)
        self.assertIn('internal fun compressionContextUsage(', model)
        bar = self.text('ui/components/AgentChatInputBar.kt')
        self.assertIn('historyTokenCount = historyTokenCount', bar)
        self.assertIn('shouldBlockSendForContextWindow(autoCompressEnabled, sendBudget)', bar)
        app = self.text('ui/app/AgentAppState.kt')
        self.assertEqual(4, app.count('= compressionContextUsage('))
        self.assertIn('if (projected && state.livePromptTokens != null && !state.livePromptIsProjected) return', app)

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
