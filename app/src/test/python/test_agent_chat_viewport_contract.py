"""Source contracts for the chat viewport above the measured composer.

Run with Python unittest; no Android SDK is required. These guard the layout
wiring, not rendered Compose geometry, IME animation, or scroll behaviour.
"""
from pathlib import Path
import re
import unittest


def code_only(source):
    # Keep offsets stable while excluding comments and strings from assertions.
    return re.sub(
        r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"',
        lambda match: " " * len(match.group()),
        source,
        flags=re.DOTALL,
    )


def balanced_end(source, start, opening, closing):
    depth = 0
    for index in range(start, len(source)):
        if source[index] == opening:
            depth += 1
        elif source[index] == closing:
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError(f"Unclosed {opening!r} at {start}")


def calls(source, name):
    for match in re.finditer(rf"\b{re.escape(name)}\s*\(", source):
        start = source.index("(", match.start())
        end = balanced_end(source, start, "(", ")")
        yield source[start + 1:end]


class AgentChatViewportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = Path(__file__).resolve().parents[3]
        cls.source = code_only((
            cls.app / "src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatBody.kt"
        ).read_text(encoding="utf-8"))
        declaration = re.search(
            r"\bfun\s+AgentConversationMessages\s*\(", cls.source
        )
        if declaration is None:
            raise AssertionError("AgentConversationMessages declaration missing")
        start = cls.source.index("(", declaration.start())
        end = balanced_end(cls.source, start, "(", ")")
        cls.parameters = cls.source[start + 1:end]
        body_start = cls.source.index("{", end)
        body_end = balanced_end(cls.source, body_start, "{", "}")
        cls.messages = cls.source[body_start + 1:body_end]
        # Exclude the declaration, so only real call sites are inspected.
        cls.call_sites = cls.source[:declaration.start()] + cls.source[body_end + 1:]

    def test_measured_bottom_inset_reaches_messages_without_caller_padding(self):
        self.assertRegex(self.parameters, r"\bbottomInset\s*:\s*Dp\b")
        self.assertRegex(
            self.source,
            r"\bval\s+bottomPadding\s*=\s*innerPadding\.calculateBottomPadding\s*\(\s*\)",
        )
        message_calls = list(calls(self.call_sites, "AgentConversationMessages"))
        self.assertEqual(len(message_calls), 1, "Expected the Scaffold messages call")
        call = message_calls[0]
        self.assertRegex(call, r"\bbottomInset\s*=\s*bottomPadding\b")
        # The measured composer/IME inset is consumed by the Box, not again by
        # its caller. The existing optional backdrop remains on the caller.
        self.assertNotRegex(call, r"\.padding\s*\(")
        self.assertRegex(call, r"\.fillMaxSize\s*\(\s*\)")
        self.assertRegex(call, r"\.layerBackdrop\s*\(\s*messageBackdrop\s*\)")

    def test_inset_reduces_box_viewport_before_clipping(self):
        boxes = [
            call for call in calls(self.messages, "Box")
            if re.search(r"\bmodifier\s*=\s*modifier\b", call)
        ]
        self.assertEqual(len(boxes), 1, "Expected one outer messages Box")
        self.assertRegex(
            boxes[0],
            r"\bmodifier\s*=\s*modifier\s*"
            r"\.padding\s*\(\s*bottom\s*=\s*bottomInset\s*,?\s*\)\s*"
            r"\.clipToBounds\s*\(\s*\)",
            "Consume the measured inset before clipping the messages viewport",
        )

    def test_inset_is_consumed_once_for_all_message_states(self):
        # No duplicate LazyColumn/FAB inset or state-conditioned inset expression:
        # streaming, paused and stopped states retain the same layout contract.
        self.assertEqual(re.findall(r"\bbottomInset\b", self.messages), ["bottomInset"])

    def test_lazy_column_keeps_only_visual_bottom_spacing(self):
        lists = list(calls(self.messages, "LazyColumn"))
        self.assertEqual(len(lists), 1, "Expected one messages LazyColumn")
        self.assertRegex(lists[0], r"\bcontentPadding\s*=\s*PaddingValues\s*\(")
        paddings = list(calls(lists[0], "PaddingValues"))
        self.assertEqual(len(paddings), 1)
        self.assertRegex(paddings[0], r"\bbottom\s*=\s*14\.dp\s*(?:,|$)")
        self.assertNotIn("bottomInset", paddings[0])

    def test_navigation_keeps_visual_gap_in_reduced_viewport(self):
        buttons = list(calls(self.messages, "ConversationTurnNavigationButton"))
        self.assertEqual(len(buttons), 1, "Expected the messages navigation button")
        self.assertRegex(
            buttons[0],
            r"\.align\s*\(\s*Alignment\.BottomCenter\s*\)\s*"
            r"\.padding\s*\(\s*bottom\s*=\s*12\.dp\s*,?\s*\)",
        )
        self.assertNotIn("bottomInset", buttons[0])

    def test_bottom_follow_retains_effective_viewport_formula(self):
        # afterContentPadding is now only the 14dp visual gap. Changing the
        # separate follow algorithm is neither necessary nor part of this fix.
        self.assertRegex(
            self.messages,
            r"\bviewportEnd\s*=\s*layoutInfo\.viewportEndOffset\s*"
            r"-\s*layoutInfo\.afterContentPadding\b",
        )

    def test_voice_panel_retains_small_inset_without_caller_padding(self):
        voice_source = code_only((
            self.app / "src/main/kotlin/io/github/mangi/eta/agent/voice/EtaVoicePanel.kt"
        ).read_text(encoding="utf-8"))
        message_calls = list(calls(voice_source, "AgentConversationMessages"))
        self.assertEqual(len(message_calls), 1, "Expected the voice panel messages call")
        self.assertRegex(message_calls[0], r"\bbottomInset\s*=\s*8\.dp\s*,")
        self.assertRegex(
            message_calls[0],
            r"\bmodifier\s*=\s*Modifier\.fillMaxSize\s*\(\s*\)\s*,?\s*$",
        )


if __name__ == "__main__":
    unittest.main()
