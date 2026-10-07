from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/agent'

class ForegroundBusyContractTest(unittest.TestCase):
    def test_gate_does_not_queue_or_wait_for_owner_release(self):
        source = (BASE / 'tool/ForegroundExclusiveGate.kt').read_text()
        for obsolete in ['await(', 'newCondition(', 'waiters', 'while (true)']:
            self.assertNotIn(obsolete, source)
        self.assertIn('ownerRunId != null && ownerRunId != id', source)
        self.assertIn('Admission.BUSY', source)
        self.assertIn('if (ownerRunId == id) ownerRunId = null', source)

    def test_rejection_is_before_tool_execution_and_explains_no_retry(self):
        source = (BASE / 'tool/AgentLocalTools.kt').read_text()
        admission = source.split('private fun executeOnSurface(', 1)[1].split('private fun executeInternal(', 1)[0]
        self.assertLess(admission.index('virtualRouted('), admission.index('ForegroundExclusiveGate.acquire('))
        self.assertIn('Admission.CLOSED', admission)
        self.assertIn('"RUN_CLOSED"', admission)
        self.assertIn('"FOREGROUND_BUSY"', admission)
        for field in ['executed', 'queued', 'retryable']:
            self.assertIn(f'.put("{field}", false)', admission)
        self.assertIn('不要自动等待、重试', admission)
        prompt = (BASE / 'model/AgentPromptBuilder.kt').read_text()
        self.assertIn('若工具返回 FOREGROUND_BUSY', prompt)
        self.assertIn('非屏幕任务仍可继续', prompt)

    def test_ask_preflight_precedes_prompt_without_replacing_final_admission(self):
        source = (BASE / 'tool/AgentLocalTools.kt').read_text()
        execute = source.split('override fun execute(', 1)[1].split('private fun resolveAskedSurface', 1)[0]
        self.assertLess(execute.index('ForegroundExclusiveGate.checkAvailability('), execute.index('resolveAskedSurface(toolCall.name)'))
        self.assertIn('ForegroundExclusiveGate.acquire(browserRunId)', source)
