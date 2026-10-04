"""Static contracts for ThinkingRow viewport recovery wiring."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = ROOT / "main" / "kotlin" / "io" / "github" / "mangi" / "eta" / "ui" / "components"
CHAT = (COMPONENTS / "ChatMessageItem.kt").read_text(encoding="utf-8")
BODY = (COMPONENTS / "AgentChatBody.kt").read_text(encoding="utf-8")
RECOVERY = (COMPONENTS / "BottomFollowViewportRecovery.kt").read_text(encoding="utf-8")


class ThinkingExpansionViewportContractTest(unittest.TestCase):
    def test_thinking_row_reports_toggle_before_mutating_target(self):
        self.assertIn("onThinkingToggle: ((String, Boolean) -> Unit)? = null", CHAT)
        self.assertIn("onToggle = onThinkingToggle", CHAT)
        callback = CHAT.split("private fun ThinkingRow(", 1)[1].split("// ── 工具调用", 1)[0]
        self.assertIn("onToggle?.invoke(message.id, !expanded)", callback)
        self.assertLess(
            callback.index("onToggle?.invoke(message.id, !expanded)"),
            callback.index("expanded = !expanded"),
        )

    def test_parent_captures_thinking_row_before_animation_relayout(self):
        self.assertIn("onThinkingToggle = if (message is ThinkingMessageUi)", BODY)
        start = BODY.index("onThinkingToggle = if (message is ThinkingMessageUi)")
        end = BODY.index("// Keep this modifier stable", start)
        window = BODY[start:end]
        self.assertIn("viewportRecovery.beginThinkingExpansion(", window)
        self.assertIn("rowKey = thinkingKey", window)
        self.assertIn("canOwnWorkExpansionViewport()", window)
        self.assertIn("scrollState.isConversationAtBottom()", window)
        self.assertIn("viewportRecovery.cancelWorkExpansion(thinkingKey)", window)

    def test_recovery_uses_the_row_key_and_next_visible_anchor(self):
        self.assertIn("fun beginThinkingExpansion(", RECOVERY)
        self.assertIn("workExpansion = WorkExpansion(rowKey, emptySet(), anchor.key, anchor.offset, expiresAtNanos)", RECOVERY)
        self.assertIn("val row = items.firstOrNull { it.key == rowKey }", RECOVERY)
        self.assertIn("val anchor = items.firstOrNull { it.index > row.index }", RECOVERY)
        self.assertIn("resolveWorkExpansionViewportStep", RECOVERY)
        self.assertIn("viewportRecovery.recover(", BODY)
        self.assertIn("canRecoverExpansion = canOwnWorkExpansionViewport", BODY)


if __name__ == "__main__":
    unittest.main()
