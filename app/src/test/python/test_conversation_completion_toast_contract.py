from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class ConversationCompletionToastContractTest(unittest.TestCase):
    def test_notice_is_success_only_and_not_virtual_only(self):
        s = (BASE / 'agent/runtime/ConversationCompletionNotice.kt').read_text()
        self.assertIn('!wasStopped && result.ok && result.error.isNullOrBlank()', s)
        self.assertNotIn('virtualDeliveryCompleted', s)
        self.assertIn(' · 已完成', s)
        self.assertIn('未命名会话', s)
        self.assertIn('@Synchronized', s)
        self.assertIn('if (runId in runs) return', s)
        self.assertLess(s.index('if (!mainHandler.post'), s.index('runs.add(runId)'))
        self.assertIn('mainHandler.post', s)
        self.assertIn('MAX_RUNS', s)

    def test_runtime_notification_is_not_overlay_or_ipc_gated(self):
        s = (BASE / 'agent/runtime/AgentRuntimeService.kt').read_text()
        persist = s.split('private fun persistRunArtifacts(', 1)[1].split('private fun postTerminalOverlay(', 1)[0]
        self.assertIn('AGENT_UI_HANDOFF_SOURCE', persist)
        self.assertIn('ConversationCompletionNotice.confirmed(result)', persist)
        self.assertIn('conversationMetadata(conversationId)', persist)
        self.assertIn('ConversationCompletionNotice.show', persist)
        self.assertNotIn('overlaySession', persist)
        self.assertNotIn('taskSurfaceMode', persist)

    def test_recovery_live_and_media_share_notice_without_changing_virtual_cleanup(self):
        s = (BASE / 'ui/app/AgentAppState.kt').read_text()
        self.assertNotIn('"任务完成"', s)
        self.assertIn('if (!recovery.alreadyApplied)', s)
        live = s.split('private fun applyRunResult(', 1)[1].split('private fun updateRunTrace', 1)[0]
        self.assertIn('acknowledgeRuntimeResult && !completionAlreadyApplied', live)
        self.assertIn('conversationTitles[conversationId]', live)
        self.assertIn('if (stoppedDuringRetry == null)', live)
        self.assertIn('VirtualCompletionNotice.append', live)
        media = s.split('private fun finishImageGenerationRun(', 1)[1].split('private fun failImageGenerationRun', 1)[0]
        self.assertIn('ConversationCompletionNotice.show', media)
        self.assertIn('conversationIdForRun(runId)', media)
        self.assertLess(media.index('setConversationStreaming(runId, false)'), media.index('ConversationCompletionNotice.show'))
