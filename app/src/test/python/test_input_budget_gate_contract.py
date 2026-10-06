"""Production wiring: all expensive input-bar budget work is inside the lazy gate."""
from pathlib import Path
import unittest
from test_agent_chat_viewport_contract import balanced_end

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class InputBudgetGateContract(unittest.TestCase):
    def test_all_budget_work_is_gated_but_ring_and_skills_are_not(self):
        bar = (ROOT / 'ui/components/AgentChatInputBar.kt').read_text()
        start = bar.index('val contextSendBlocked = contextSendBlocked(measuredContextTokens, autoCompressEnabled)')
        opening = bar.index('{', start)
        end = balanced_end(bar, opening, '{', '}')
        body = bar[opening:end]
        outside = bar[:opening] + bar[end:]
        for token in ('val historyTokenCount = remember(history)',
                      'val localHistoryTokenCount = remember(history, supportsVision, supportsVideo)',
                      'val sendBudget = remember(', 'AgentContextBudget.countMessage(it)',
                      'AgentRequestTokenEstimate.history(', 'compressionContextUsage('):
            self.assertIn(token, body)
            self.assertNotIn(token, outside)
        self.assertIn('liveContextUsage(', outside)
        self.assertIn('listSkillsForManagement()', outside)
        self.assertNotIn('LaunchedEffect', body)
        self.assertNotIn('withContext', body)
        self.assertTrue(body.rstrip().endswith('sendBudget'))

    def test_gate_preserves_nonnull_measurement_and_original_threshold_policy(self):
        gate = (ROOT / 'ui/components/ContextSendBudgetGate.kt').read_text()
        self.assertIn('budget: @Composable () -> AgentContextUsageUi', gate)
        self.assertIn('if (measuredContextTokens != null && !autoCompressEnabled)', gate)
        self.assertIn('shouldBlockSendForContextWindow(autoCompressEnabled, budget())', gate)
        self.assertIn('else {\n    false\n}', gate)
        self.assertNotIn('measuredContextTokens >', gate)
