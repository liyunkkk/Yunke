"""Source wiring checks supplement the JVM selection/schema tests; not token-accuracy tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class RequestOverheadPreviewContractTest(unittest.TestCase):
    def text(self, path):
        return (ROOT / path).read_text()

    def test_preview_is_local_and_uses_owner_config_and_real_schemas(self):
        preview = self.text('agent/delegation/SubAgentRequestPreview.kt')
        for shared in ('ChildWorkerConfigResolver.resolve(', 'providerLookup =', 'modelResolver =',
                       'RuntimeConfigRepository.buildRuntimeConfig(',
                       'AgentChildWorkerAvailability.configuredChildren(',
                       'SubAgentPreferences.workerDescription(', 'SubAgentTools.appendTo(',
                       'ExistingChildTaskTools.appendTo(', 'AgentChildWorkerAvailability.appendToPrompt('):
            self.assertIn(shared, preview)
        for forbidden in ('SubAgentManager(', 'SubAgentCoordinator(', 'SubAgentWorkspace(',
                          'SubAgentPreferences.profiles(', '.selection.resolve(', 'resolveOAuth('):
            self.assertNotIn(forbidden, preview)
        app = self.text('ui/app/AgentAppState.kt')
        estimation = app.split('private suspend fun estimateRequestOverhead(', 1)[1].split(
            'private fun syncBilledOverhead(', 1)[0]
        self.assertIn('conversationSubAgentPreferences.previewSnapshot(owner)', estimation)
        self.assertIn('McpRunSnapshot.appendCachedModelTools(', estimation)
        self.assertIn('SubAgentRequestPreview.appendTo(', estimation)
        self.assertIn('AgentContextBudget.countTokens(childPrompt)', estimation)
        self.assertNotIn('runtimeConfigForBoundModel(', estimation)
        refresh = app.split('fun refreshRequestOverhead(configurationChanged: Boolean = true)', 1)[1].split(
            'private suspend fun estimateRequestOverhead(', 1)[0]
        self.assertNotIn('runtimeConfigForBoundModel(', refresh)
        self.assertNotIn('getOrDefault(0)', refresh)
        self.assertIn('overheadSelection.complete(overheadRequest, currentOverheadBinding(), tokens)', refresh)

    def test_promotion_replaces_pending_draft_request(self):
        app = self.text('ui/app/AgentAppState.kt')
        promotion = app.split('conversationDrafts.promote(id)', 1)[1].split('assignPendingFolder(id)', 1)[0]
        self.assertLess(promotion.index('selectedConversationId = id'), promotion.index('refreshRequestOverhead()'))
        self.assertIn('conversationSubAgentPreferences.revision.collectLatest { refreshRequestOverhead() }', app)

    def test_send_reestimates_frozen_config_and_publishes_only_on_main(self):
        app = self.text('ui/app/AgentAppState.kt')
        run = app.split('private fun launchConversationRun(', 1)[1].split('private fun ', 1)[0]
        self.assertNotIn('val runOverhead = requestOverheadTokens', run)
        self.assertIn('estimateRequestOverhead(config, runAssistant, SubAgentConfigKey.Conversation(conversationId), runProviders)', run)
        self.assertRegex(run, r'withContext\(Dispatchers.Main\)\s*\{\s*if \(runOverhead != null[^\n]*\)\s*\{\s*runOverheadTokens\[runId\] = runOverhead')
        self.assertIn('val estimatedTokens = runOverhead?.let { overhead ->', run)
        self.assertIn('runOverhead != null && state.cloudHistoryTokens != null', run)
        self.assertNotIn('runOverheadTokens[runId] ?: requestOverheadTokens', app)

    def test_configuration_changes_invalidate_before_async_estimation(self):
        app = self.text('ui/app/AgentAppState.kt')
        refresh = app.split('fun refreshRequestOverhead(configurationChanged: Boolean = true)', 1)[1].split(
            'private suspend fun estimateRequestOverhead(', 1)[0]
        self.assertLess(refresh.index('overheadConfigurationGeneration++'), refresh.index('overheadSelection.begin(binding)'))
        self.assertIn('configurationGeneration = overheadConfigurationGeneration', app)
        self.assertIn('val assistant = requestOverheadAssistant()', refresh)
        self.assertIn('requestOverheadAssistant() != assistant', refresh)

    def test_selected_assistant_scope_does_not_wait_for_remote_sync(self):
        app = self.text('ui/app/AgentAppState.kt')
        apply = app.split('private fun applyConversationAssistant(', 1)[1].split('private fun newDraftChatState()', 1)[0]
        self.assertLess(apply.index('refreshRequestOverhead()'), apply.index('scope.launch(Dispatchers.IO)'))
        self.assertIn('AssistantRepository.profile(resolvedAssistantId(state))', app)
        self.assertIn('val runAssistant = requestOverheadAssistant(state)', app)


if __name__ == '__main__':
    unittest.main()
