"""Static source-wiring guards; these do not execute Kotlin or build Android."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class ContextPrecisionContractTest(unittest.TestCase):
    def read(self, relative):
        return (ROOT / relative).read_text()

    def test_empty_tools_and_empty_calls_do_not_create_budget(self):
        code = self.read('agent/model/AgentRequestTokenEstimate.kt')
        self.assertIn('if (definitions.length() == 0) 0', code)
        self.assertIn('calls !== JSONObject.NULL', code)
        self.assertIn('calls is JSONArray && calls.length() == 0', code)

    def test_runtime_reuses_the_single_prepared_snapshot(self):
        code = self.read('agent/model/AgentLoop.kt')
        self.assertEqual(1, code.count('AgentRequestMediaPolicy.filter('))
        self.assertIn('AgentRequestTokenEstimate.filtered(filteredMessages, roundTools)', code)
        self.assertIn('filteredMessages, roundTools, sessionId)', code)
        self.assertIn('val preparedRequestTokens = if (publishLocalEstimate)', code)
        self.assertIn('val localEstimate = preparedRequestTokens?.takeIf', code)

    def test_calibration_does_not_mix_prepared_tokens_with_boundary_tokens(self):
        code = self.read('agent/model/AgentLoop.kt')
        self.assertIn('val requestLocal = localRequestTokens()', code)
        self.assertIn('silentBudget.requestStarted(requestLocal)', code)
        self.assertIn('AgentRequestTokenEstimate.boundary(messages, currentRoundTools, config.supportsVision, config.supportsVideo)', code)
        self.assertIn('AgentRequestTokenEstimate.fixed(messages, systemCount, roundTools)', code)
        self.assertNotIn('(requestLocal - requestHistoryTokens)', code)

    def test_lightweight_budget_uses_stat_limits_not_media_reads(self):
        code = self.read('agent/model/AgentRequestTokenEstimate.kt')
        for forbidden in ('readBytes(', 'readText(', '.hydrate(', 'previewFromFile(', 'AgentRequestMediaPolicy.filter('):
            self.assertNotIn(forbidden, code)
        self.assertIn('size in 1..MAX_AGENT_IMAGE_BYTES.toLong()', code)
        self.assertIn('video && size in 1..MAX_AGENT_VIDEO_BYTES.toLong()', code)
        self.assertIn('maxOf(85, (size / 4096).toInt())', code)

    def test_ui_preserves_raw_calibration_and_separate_capability_preview(self):
        bar = self.read('ui/components/AgentChatInputBar.kt')
        self.assertIn('remember(history, supportsVision, supportsVideo)', bar)
        self.assertIn('historyTokenCount = localHistoryTokenCount', bar)
        self.assertIn('historyTokenCount = historyTokenCount', bar)
        model = self.read('ui/model/AgentModelPickerUiState.kt')
        self.assertIn('pendingFileReferences, pendingConversationMentions, localHistoryTokenCount', model)
        self.assertIn('history.sumOf { AgentContextBudget.countMessage(it) }', model)
        self.assertIn('if (image.isVideo) video || vision else vision', model)

if __name__ == '__main__':
    unittest.main()
