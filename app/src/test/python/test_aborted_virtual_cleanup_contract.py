"""Wiring checks supplement (not replace) the executable Kotlin cleanup tests."""
import pathlib
import re
import unittest

AGENT = pathlib.Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/agent'

class AbortedVirtualCleanupContractTest(unittest.TestCase):
    def test_abort_is_exact_run_only_and_clears_delivery_before_finish(self):
        source = (AGENT / 'device/VirtualDisplaySession.kt').read_text()
        abort = source.split('@Synchronized fun onRunAborted(', 1)[1].split(
            '/** Called once when the owning run closes', 1)[0]
        self.assertIn('sessions[runId] ?: return null', abort)
        self.assertNotIn('start(', re.sub(r'//[^\n]*', '', abort))
        self.assertNotIn('onRunClosed(', abort)
        recovery_guard = abort.index('if (s.cleanupOnly) return reply(false, "RECOVERY_REQUIRED")')
        self.assertLess(recovery_guard, abort.index('s.abortCleanupAttempted = true'))
        self.assertLess(recovery_guard, abort.index('.putString("kept", "[]").commit()'))
        keep = source.split('@Synchronized fun keep(', 1)[1].split('@Synchronized fun deliveryReceipt', 1)[0]
        self.assertIn('if (s.abortCleanupAttempted) return reply(false, "SESSION_NOT_ACTIVE")', keep)
        self.assertIn('s.handoffBudget.blocked || s.handoffState != null || s.handoffSelection != null', abort)
        self.assertIn('f.finishing || f.handoffComplete || f.releaseAttempted || f.mutationUncertain', abort)
        self.assertLess(abort.index('.putString("kept", "[]").commit()'), abort.index('s.kept.clear()'))
        self.assertLess(abort.index('s.kept.clear()'), abort.index('finish(runId, context)'))
        self.assertIn('s.cleanupOnly = true', abort)
        self.assertIn('if (s.kept.isEmpty() && !s.cleanupOnly)', source)

    def test_failed_run_invokes_cleanup_before_releasing_tools_and_preserves_error(self):
        source = (AGENT / 'runtime/AgentRuntimeRunExecutor.kt').read_text()
        cleanup = source.index('VirtualDisplayAbortCleanup.failureCode')
        self.assertIn('localTools?.cleanupAbortedVirtualSession()', source[cleanup:cleanup + 220])
        self.assertLess(cleanup, source.index('runCatching { toolsBinding?.close() }'))
        note = source.split('cleanupFailure?.let { code ->', 1)[1].split('result = result.copy(virtualDeliveryCompleted', 1)[0]
        self.assertIn('listOfNotNull(result.error?.takeIf', note)
        self.assertIn('ok = false', note)

if __name__ == '__main__':
    unittest.main()
