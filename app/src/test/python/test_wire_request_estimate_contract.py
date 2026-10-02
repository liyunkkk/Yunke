"""Source wiring only; runtime estimates are covered by Kotlin tests in cloud CI."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class WireRequestEstimateContractTest(unittest.TestCase):
    def text(self, path):
        return (ROOT / path).read_text()

    def test_each_transport_uses_final_body_before_network(self):
        for name in ('OpenAiResponsesProvider', 'OpenAiChatCompletionsProvider', 'AnthropicMessagesProvider'):
            source = self.text(f'agent/model/{name}.kt')
            complete = source.split('override fun complete(', 1)[1].split('internal fun buildRequestJson(', 1)[0]
            self.assertEqual(1, complete.count('buildRequestJson('))
            self.assertEqual(1, complete.count('requestJson.toString()'))
            self.assertIn('AgentWireRequestEstimate.publish(requestJson, capabilities.endpoint,', complete)
            self.assertLess(complete.index('AgentWireRequestEstimate.publish('), complete.index('= readStreaming'))

    def test_display_and_silent_inputs_remain_separate(self):
        loop = self.text('agent/model/AgentLoop.kt')
        self.assertIn('silentBudget.requestStarted(requestLocal)', loop)
        self.assertIn('silentBudget.tokens(localRequestTokens())', loop)
        estimate = loop.split('if (providerEvent is ProviderEvent.RequestEstimate)', 1)[1].split('if (providerEvent is ProviderEvent.Usage)', 1)[0]
        self.assertIn('projected = true', estimate)
        self.assertNotIn('silentBudget.measured(', estimate)
        app = self.text('ui/app/AgentAppState.kt')
        self.assertIn('if (projected && state.livePromptTokens != null && !state.livePromptIsProjected) return', app)
        bar = self.text('ui/components/AgentChatInputBar.kt')
        self.assertIn('requestOverheadTokens = previewRequestOverheadTokens ?: requestOverheadTokens', bar)
        send = bar.split('val sendBudget = remember(', 1)[1].split('val contextSendBlocked', 1)[0]
        self.assertNotIn('previewRequestOverheadTokens', send)
        self.assertIn('localHistoryTokenCount = localHistoryTokenCount', send)
        preview = self.text('ui/model/AgentModelPickerUiState.kt').split('internal fun compressionContextUsage(', 1)[1]
        self.assertIn('projectedContextTokens = null', preview)

    def test_no_ciphertext_tokenizer_or_payload_logging(self):
        estimator = self.text('agent/model/AgentWireRequestEstimate.kt')
        self.assertIn('encrypted += item.optString("encrypted_content").length', estimator)
        self.assertNotIn('countTokens(body.toString())', estimator)
        self.assertNotIn('countTokens(item.optString("encrypted_content"))', estimator)
        self.assertNotIn('put("payload"', estimator)
        self.assertIn('AgentRequestContextDiagnostics.wireBodyFields(shape)', estimator)

if __name__ == '__main__':
    unittest.main()
