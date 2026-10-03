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
        launch = app.split('private fun launchConversationRun(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('contextStateForRequestHistory(state, history)', launch)
        self.assertIn('contextState.copy(', launch)
        self.assertNotIn('takeUnless { historyRewritten }', launch)
        history = app.split('private fun contextStateForRequestHistory(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('prefix[index].copy(turnId = "") == history[index].copy(turnId = "")', history)
        self.assertIn('if (matchesRequestPrefix(state.history)) return state', history)
        self.assertIn('AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(', history)
        self.assertIn('if (matchesRequestPrefix(committedHistory)) return state', history)
        self.assertNotIn('startsWith(', history)
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
        # Boundaries keep the latest actual/evidence on the same model and route. Only an
        # invalidated route may reach the clearing branch; no boundary generates display deltas.
        self.assertIn('runUsageOwners[runId] == (state.providerId to state.modelId)', revoke)
        self.assertIn('runUsageRoutes[runId] == route', revoke)
        guard = '(state.cloudRouteSignature == null || state.cloudRouteSignature == route)) return'
        self.assertIn(guard, revoke)
        self.assertLess(revoke.index(guard), revoke.index('livePromptTokens = null'))
        self.assertIn('invalidatedUsageRuns.add(runId)', revoke)
        self.assertNotIn('receiptPredictionTokens = estimate', revoke)
        self.assertNotIn('RequestOverheadCalibration.receiptEstimate(', revoke)
        self.assertNotIn('RequestOverheadCalibration.receiptEstimate(', launch)
        terminal = app.split('private fun applyRunResult(', 1)[1].split('private fun ', 1)[0]
        self.assertNotIn('current.cloudReceiptRequestId !=', terminal)
        self.assertIn('revokeContextActual(runId)', terminal)
        budget = app.split('private fun budgetReceiptTokens(', 1)[1].split('private fun ', 1)[0]
        self.assertNotIn('receiptPredictionTokens', budget)
        self.assertNotIn('overheadCalibrationTokens', budget)

    def test_custom_actual_scope_is_separate_from_fail_closed_learning_and_budget(self):
        app = self.source('ui/app/AgentAppState.kt')
        actual = app.split('private fun contextRouteSignature(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('actual-local-v1:', actual)
        for field in ('provider.baseUrl', 'provider.customHeaders', 'provider.customBody',
                      'provider.sessionGatewayJson', 'Model.serializer()'):
            self.assertIn(field, actual)
        self.assertIn('SHA-256', actual)
        record = app.split('private fun recordContextEstimateReceipt(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('val route = contextLearningRouteSignature(state) ?: return', record)
        budget = app.split('private fun budgetReceiptTokens(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('state.cloudRouteSignature == contextLearningRouteSignature(state)', budget)
        live = app.split('private fun updateLivePromptTokens(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('val route = runUsageRoutes[runId] ?: return', live)
        self.assertIn('if (route != contextRouteSignature(state)) return', live)
        updates = app.split('private fun updateSelectionProviders(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('invalidatedUsageRuns.add(runId)', updates)
        self.assertIn('updateConversation(id, state, updateTimestamp = false)', updates)
        observer = app.split('private fun observeRuntimeSelection(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('updateSelectionProviders(providers)', observer)
        diagnostic_actual = app.split('val uiActual = billedPromptTokens(contextState)', 1)[1].split('val uiTokens', 1)[0]
        self.assertIn('contextState.cloudRouteSignature == contextRouteSignature(contextState)', diagnostic_actual)
        self.assertNotIn('validReceiptRoute', diagnostic_actual)

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
