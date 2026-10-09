"""副屏节点自愈接线：所有取树走单点入口，失效判定与重绑必须受保护后端门控。"""
import pathlib
import unittest

AGENT = pathlib.Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/agent'


class VirtualDisplayAccessibilitySelfHealContractTest(unittest.TestCase):
    def test_all_node_captures_use_the_single_entry(self):
        session = (AGENT / 'device/VirtualDisplaySession.kt').read_text()
        self.assertIn('captureVirtualNodes(context, s, NODE_LIMIT, c.displayId)', session)
        self.assertIn('captureVirtualNodes(context, s, AFTER_ACTION_NODE_LIMIT, displayId)', session)
        # 旧的直连取树写法不得残留（否则绕过自愈）。
        self.assertNotIn('accessibility.captureNodeSnapshot', session)
        self.assertNotIn('AgentAccessibilityService.current()?.captureNodeSnapshot(AFTER_ACTION', session)

    def test_recovery_is_once_per_session_and_only_when_windows_are_unusable(self):
        session = (AGENT / 'device/VirtualDisplaySession.kt').read_text()
        body = session.split('private fun captureVirtualNodes(', 1)[1].split(
            'private fun afterActionSummary(', 1)[0]
        self.assertIn('s.accessibilityRecoveryAttempted = true', body)
        self.assertIn('AgentAccessibilityKeeper.forceRecoveryForGuiOperation(context)', body)
        self.assertIn('AgentAccessibilityService.defaultDisplayWindowsUsable()', body)
        # 应用窗口单纯未就绪时不得重绑。
        self.assertIn('if (s.accessibilityRecoveryAttempted || AgentAccessibilityService.defaultDisplayWindowsUsable()) return null', body)

    def test_health_provider_requires_real_window_access(self):
        provider = (AGENT / 'accessibility/AgentAccessibilityHealthProvider.kt').read_text()
        self.assertIn('accessibilityConnectionHealthy(', provider)
        self.assertIn('AgentAccessibilityService.defaultDisplayWindowsUsable()', provider)

    def test_force_recovery_is_protection_gated(self):
        keeper = (AGENT / 'accessibility/AgentAccessibilityKeeper.kt').read_text()
        body = keeper.split('internal fun forceRecoveryForGuiOperation(', 1)[1].split(
            'private fun awaitUsableWindowContent(', 1)[0]
        self.assertIn('AccessibilityProtectionClient.isEnabled(context)', body)
        self.assertIn('requestRecoveryBlocking(context)', body)
        self.assertIn('ACCESSIBILITY_PROTECTION_UNAVAILABLE', body)


if __name__ == '__main__':
    unittest.main()
