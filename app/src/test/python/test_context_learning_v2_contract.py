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
            self.assertIn('contextDisplayPolicy(state)', ui)
            self.assertNotIn('filterIsInstance<ContextCompactedMessageUi>', ui)
        policy = self.source('ui/model/RequestOverheadCalibration.kt').split('internal fun contextDisplayPolicy(', 1)[1]
        self.assertIn('awaitingReceipt = state.contextAwaitingReceipt', policy)
        self.assertIn('firstTurn = !state.contextHasStarted', policy)
        self.assertNotIn('history.isEmpty()', policy)
        self.assertNotIn('messages.isEmpty()', policy)
        app = self.source('ui/app/AgentAppState.kt')
        launch = app.split('private fun launchConversationRun(', 1)[1].split('private fun ', 1)[0]
        self.assertNotIn('contextHasStarted = true', launch)
        terminal = app.split('private fun setConversationStreaming(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('contextHasStarted = state.contextHasStarted || !isStreaming', terminal)

    def test_retry_preserves_provenance_and_partial_usage_cannot_mix_requests(self):
        app = self.source('ui/app/AgentAppState.kt')
        self.assertIn('livePromptTokens = state.livePromptTokens.takeUnless { historyRewritten }', app)
        self.assertIn('validReceiptRoute && relatedHistory && !state.contextAwaitingReceipt', app)
        self.assertIn('billedContextTokens = receiptAnchorForDelta', app)
        self.assertIn('if (!replaying) recordContextEstimateReceipt', app)
        evidence = self.source('ui/model/ContextReceiptEvidence.kt')
        self.assertIn('it.requestId == requestId && it.input == input', evidence)
        for event in ('ProviderRequestStarted', 'ModelRetryScheduled'):
            boundary = app.split('is AgentEvent.' + event + ' ->', 1)[1].split('\n            is AgentEvent.', 1)[0]
            self.assertIn('revokeContextActual(runId)', boundary)
        revoke = app.split('private fun revokeContextActual(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('livePromptTokens = null', revoke)
        self.assertIn('contextReceiptEvidence = null', revoke)
        self.assertIn('cloudReceiptRequestId = null', revoke)
        self.assertIn('contextBudgetReceiptTokens = cloud', revoke)
        self.assertIn('receiptPredictionTokens = estimate', revoke)
        self.assertIn('RequestOverheadCalibration.receiptEstimate(', revoke)
        self.assertIn('sameRoute && !state.contextAwaitingReceipt', revoke)
        self.assertIn('runUsageOwners[runId] != (state.providerId to state.modelId)', revoke)
        budget = app.split('private fun budgetReceiptTokens(', 1)[1].split('private fun ', 1)[0]
        self.assertNotIn('receiptPredictionTokens', budget)
        self.assertNotIn('overheadCalibrationTokens', budget)

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
