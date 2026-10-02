import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[4]
MODEL = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/agent/model'


class OpaqueCompactionContract(unittest.TestCase):
    def test_all_ui_entry_points_supply_the_session_model(self):
        source = (ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt').read_text()
        calls = re.findall(r'(?<!fun )tryCompressHistory\((.*?)\)\s*(?:\n|;)', source, re.S)
        self.assertEqual(3, len(calls))
        self.assertTrue(all('sourceModelConfig =' in call for call in calls))
        body = source.split('private suspend fun tryCompressHistory(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('config.progressBudget()', body)
        self.assertIn('countsOpaque = { config.replayedOpaqueItems(it) > 0 }', body)

    def test_one_cut_drives_summary_and_json_reconstruction(self):
        source = (MODEL / 'AgentContextCompactor.kt').read_text()
        body = source.split('fun compactMessages(', 1)[1].split('internal fun rebuildConversation(', 1)[0]
        self.assertIn('keepStartOverride = cut', body)
        self.assertIn('val tailSize = history.size - cut', body)
        self.assertNotIn('recentKeepStartIndex(', body)
        self.assertIn('val sourceModelConfig: AgentModelClient.ModelConfig? = null', source)
        self.assertIn('val summaryTokenBudget: Int = 0', source)
        self.assertNotIn('summaryProgressBudget(config.compressModelConfig', source)

    def test_missing_cut_does_not_disable_future_cloud_pressure(self):
        source = (MODEL / 'AgentLoop.kt').read_text()
        branches = re.findall(r'if \(plan.stopReason != null\) \{(.*?)\n\s*\}', source, re.S)
        self.assertEqual(3, len(branches))
        self.assertTrue(all('skipIneffectiveAutoCompact = true' not in body for body in branches))
        self.assertIn('replayedOpaque(filteredMessages, config)', source)


if __name__ == '__main__':
    unittest.main()
