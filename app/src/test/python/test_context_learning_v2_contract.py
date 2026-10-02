"""Wiring checks supplement Kotlin behavior tests; they do not claim Android execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class ContextLearningV2Contract(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_compaction_state_is_checkpointed_not_inferred_from_visible_pages(self):
        store = self.source('ui/app/AgentConversationStore.kt')
        self.assertIn('state.contextHasStarted, state.contextAwaitingReceipt, state.cloudRouteSignature', store)
        self.assertIn('CloudUsageReceiptCodec.decodeDisplayState(', store)
        for screen in ('home/AgentHomeScreen', 'chat/AgentChatScreen'):
            ui = self.source('ui/screens/' + screen + '.kt')
            self.assertIn('awaitingReceipt = state.contextAwaitingReceipt', ui)
            self.assertNotIn('filterIsInstance<ContextCompactedMessageUi>', ui)

    def test_retry_preserves_provenance_and_partial_usage_cannot_mix_requests(self):
        app = self.source('ui/app/AgentAppState.kt')
        self.assertIn('livePromptTokens = state.livePromptTokens.takeUnless { historyRewritten }', app)
        self.assertIn('validReceiptRoute && relatedHistory && !state.contextAwaitingReceipt', app)
        self.assertIn('billedContextTokens = receiptAnchorForDelta', app)
        self.assertIn('if (!replaying) recordContextEstimateReceipt', app)
        evidence = self.source('ui/model/ContextReceiptEvidence.kt')
        self.assertIn('it.requestId == requestId && it.input == input', evidence)
        self.assertIn('is AgentEvent.ProviderRequestStarted ->', app)
        self.assertIn('state.copy(contextReceiptEvidence = null, cloudReceiptRequestId = null)', app)

    def test_diagnostics_abandon_compression_or_retry_pairing(self):
        app = self.source('ui/app/AgentAppState.kt')
        compact = app.split('private fun applyRuntimeCompactedHistory(', 1)[1].split('private fun scheduleAutoCompress(', 1)[0]
        self.assertIn('contextEstimateDiagnostics.clear(runId)', compact)
        diag = self.source('ui/model/ContextEstimateDiagnostics.kt')
        self.assertIn('round == snapshot.round && contextEpoch == snapshot.contextEpoch', diag)
        self.assertIn('historyTokens == snapshot.historyTokensEst && overheadTokens == snapshot.overheadTokensEst', diag)
        self.assertIn('if (matched) put("estimate_error"', diag)
        self.assertNotIn('put("new_offset"', diag)

if __name__ == '__main__':
    unittest.main()
