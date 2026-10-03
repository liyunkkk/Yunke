"""Model-only source wiring contracts; does not execute or compile Kotlin.

The Kotlin tests exercise the receipt/callback/tool/final ordering. These independent
checks guard the wiring when only the repository's Python suite can be run.
"""
from pathlib import Path
import unittest

MODEL = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/agent/model'
TESTS = Path(__file__).resolve().parents[1] / 'kotlin/io/github/mangi/eta/agent/model'


class SilentReceiptPressureContractTest(unittest.TestCase):
    def setUp(self):
        self.budget = (MODEL / 'AgentSilentContextBudget.kt').read_text()
        self.loop = (MODEL / 'AgentLoop.kt').read_text()

    def test_plausible_receipt_is_accepted_without_local_growth_confirmation(self):
        for removed in ('withinGrowth', 'GROWTH_SLACK', 'candidateInput', 'candidateLocal',
                        'candidateGeneration', 'CANDIDATE', 'REANCHORED'):
            self.assertNotIn(removed, self.budget)
        measured = self.budget.split('fun measured(', 1)[1].split('private fun accept(', 1)[0]
        self.assertIn('accept(inputTokens)\n        record(SilentReceiptDecision.ACCEPTED, inputTokens)', measured)
        for local_estimate in ('requestLocal', 'measuredLocal', 'measuredInput', 'requestGeneration'):
            self.assertNotIn(local_estimate, measured)
        accept = self.budget.split('private fun accept(', 1)[1].split('fun seed(', 1)[0]
        self.assertIn('cloudInput = inputTokens', accept)
        self.assertIn('anchorIsSeed = false', accept)

    def test_non_positive_window_and_invalid_cache_guards_precede_acceptance(self):
        measured = self.budget.split('fun measured(', 1)[1].split('private fun accept(', 1)[0]
        acceptance = measured.index('accept(inputTokens)')
        for guard in ('inputTokens == null || inputTokens <= 0',
                      'AgentBilledPromptPlausibility.fitsWindow(inputTokens, window)',
                      'AgentBilledPromptPlausibility.isInflatedCacheRead(inputTokens, cachedTokens, window)'):
            self.assertLess(measured.index(guard), acceptance)
        for rejection in ('REJECTED_NON_POSITIVE', 'REJECTED_OVER_WINDOW', 'REJECTED_INFLATED_CACHE'):
            self.assertRegex(measured, rf'record\(SilentReceiptDecision\.{rejection}, [^\n]+\)\s+return')

    def test_usage_queues_waiting_in_same_provider_callback_without_compressing(self):
        usage = self.loop.split('if (providerEvent is ProviderEvent.Usage) {', 1)[1].split('continuationReasoning.visibleEvent(', 1)[0]
        self.assertLess(usage.index('silentBudget.measured(lastUsage?.inputTokens,'),
                        usage.index('noteRingPressure(attemptRound)'))
        self.assertNotIn('maybeCompactBeforeRound(', usage)
        self.assertNotIn('applyCompaction(', usage)
        note = self.loop.split('private fun noteRingPressure(', 1)[1].split('private fun releaseAutoCompactWait(', 1)[0]
        self.assertIn('val cloud = silentBudget.cloudTokens() ?: return', note)
        self.assertIn('cloud >= AgentContextCompactor.autoPressureTokens(window)', note)
        self.assertLess(note.index('autoCompactLatched = true'),
                        note.index('onEvent(AgentEvent.AutoCompactWaiting(round))'))
        self.assertNotIn('localRequestTokens()', note)
        self.assertNotIn('provider.complete(', note)

    def test_request_attribution_and_unknown_seed_semantics_remain(self):
        self.assertIn('lastUsage = null // A new request must not inherit missing fields', self.loop)
        self.assertIn('ProviderEvent.RequestStarted) lastUsage = null', self.loop)
        self.assertIn('inputTokens = incoming.inputTokens ?: previous?.inputTokens', self.loop)
        # Late callbacks from a replaced provider attempt still cannot overwrite this request.
        retry = (MODEL / 'AgentModelRetry.kt').read_text()
        self.assertIn('deliveryGate.deliver {', retry)
        self.assertIn('deliveryGate.close()', retry)
        seed = self.budget.split('fun seed(', 1)[1].split('fun cloudTokens()', 1)[0]
        self.assertNotIn('cloudInput =', seed)
        self.assertNotIn('learnScale(', seed)
        self.assertIn('anchorIsSeed = true', seed)
        self.assertIn('fun hasTargetReceipt(): Boolean = measuredInput != null && !anchorIsSeed', self.budget)
        self.assertIn('val guarded = if (silentBudget.hasTargetReceipt()) calibrated else maxOf(local, calibrated)', self.loop)

    def test_tool_batch_and_final_safe_edges_keep_existing_hard_protection(self):
        top = self.loop.split('roundLoop@ while (true) {', 1)[1].split('modelRetry.complete(', 1)[0]
        self.assertIn('maybeCompactBeforeRound(round)', top)
        self.assertIn('while (requestOverBudget() || overflowPending)', top)
        self.assertIn('blocked = true', top)
        tools = self.loop.split('val outcomes = mutableListOf<ToolOutcome>()', 1)[1].split('// 上游在输出上限处截断', 1)[0]
        self.assertLess(tools.index('appendToolOutcomes(round, outcomes)'), tools.index('round += 1'))
        self.assertNotIn('maybeCompactBeforeRound(', tools)
        final = self.loop.split('// Natural completion is not an interrupted reply.', 1)[1].split('private fun', 1)[0]
        self.assertLess(final.index('maybeCompactBeforeRound(round)'), final.index('AgentEvent.RunFinished('))
        self.assertNotIn('provider.complete(', final)
        self.assertNotIn('modelRetry.complete(', final)

    def test_kotlin_regressions_cover_observed_jump_order_invalid_and_missing_receipts(self):
        budget_tests = (TESTS / 'AgentSilentContextBudgetTest.kt').read_text()
        self.assertIn('observed272kJumpUpdatesCloudOnTheSameRequestWithoutConfirmation', budget_tests)
        self.assertIn('budget.measured(193_223, 272_000)', budget_tests)
        self.assertIn('budget.measured(230_402, 272_000)', budget_tests)
        self.assertIn('assertEquals(230_402, budget.cloudTokens())', budget_tests)
        runtime_tests = (TESTS / 'AgentAutomaticCompactionTest.kt').read_text()
        for test in ('observedReceiptJumpWaitsInItsCallbackAndCompactsAtFinalWithoutAnotherRequest',
                     'observedReceiptJumpQueuesBeforeToolsAndCompactsOnlyAfterWholeBatchBeforeNextRequest',
                     'usageLessNextRequestDoesNotInheritInputFromThePreviousReceipt',
                     'invalidCurrentRequestReceiptsNeverQueuePressure'):
            self.assertIn('fun ' + test + '()', runtime_tests)
        self.assertIn('afterUsage(requests.size)', runtime_tests)
        self.assertIn('assertTrue(started in 0 until nextRequest)', runtime_tests)
        self.assertIn('assertEquals(2, provider.requests.size)', runtime_tests)


if __name__ == '__main__':
    unittest.main()
