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
        # The measured composer/IME inset is consumed inside the messages list,
        # never again by its caller. The composer surround is transparent, so
        # the caller no longer records a frosted backdrop layer.
        self.assertNotRegex(call, r"\.padding\s*\(")
        self.assertRegex(call, r"\.fillMaxSize\s*\(\s*\)")
        self.assertNotIn("layerBackdrop", call)

    def test_composer_floats_over_the_full_height_list(self):
        # The composer surround is transparent: the viewport is not shortened,
        # so messages remain visible around and behind the floating composer.
        boxes = [
            call for call in calls(self.messages, "Box")
            if re.search(r"\bmodifier\s*=\s*modifier\b", call)
        ]
        self.assertEqual(len(boxes), 1, "Expected one outer messages Box")
        head = boxes[0].split("{", 1)[0]
        self.assertNotRegex(head, r"\.padding\s*\(")
        self.assertRegex(head, r"\.clipToBounds\s*\(\s*\)")

    def test_following_output_lifts_the_tail_instead_of_clipping_it(self):
        # While following streamed output, the part the follow scroll has not
        # caught up with yet is lifted in the draw layer so the tail rests at the
        # 14dp line above the composer, with its card edge visible. Clipping at
        # the rest line remains only as the fallback when the tail is not visible.
        lists = list(calls(self.messages, "LazyColumn"))
        self.assertRegex(
            lists[0],
            r"graphicsLayer\s*\{[^}]*if\s*\(\s*shouldLiftTail\s*\)\s*\{[^}]*"
            r"resolveFollowTailLag\s*\(\s*true\s*,\s*scrollState\.followTailOverflow\(\)\s*\)\.liftPx",
        )
        boxes = [
            call for call in calls(self.messages, "Box")
            if re.search(r"\bmodifier\s*=\s*modifier\b", call)
        ]
        draw = boxes[0]
        self.assertRegex(draw, r"if\s*\(\s*shouldLiftTail\s*\)")
        self.assertRegex(
            draw,
            r"resolveFollowTailLag\s*\(\s*true\s*,\s*scrollState\.followTailOverflow\(\)\s*\)",
        )
        self.assertRegex(draw, r"if\s*\(\s*lag\s*==\s*FollowTailLag\.Unknown\s*\)")
        self.assertRegex(
            draw,
            r"size\.height\s*-\s*\(\s*bottomInset\s*\+\s*ConversationComposerGap\s*\)\.toPx\(\)",
        )
        self.assertRegex(draw, r"clipRect\s*\(\s*bottom\s*=\s*restLine")

    def test_inset_is_consumed_by_clip_list_padding_and_navigation(self):
        self.assertEqual(len(re.findall(r"\bbottomInset\b", self.messages)), 3)

    def test_lazy_column_rests_above_the_composer(self):
        lists = list(calls(self.messages, "LazyColumn"))
        self.assertEqual(len(lists), 1, "Expected one messages LazyColumn")
        paddings = list(calls(lists[0], "PaddingValues"))
        self.assertEqual(len(paddings), 1)
        # The resting line and the streaming clip line are the same constant.
        self.assertRegex(paddings[0], r"\bbottom\s*=\s*ConversationComposerGap\s*\+\s*bottomInset\b")
        self.assertRegex(self.source, r"private\s+val\s+ConversationComposerGap\s*=\s*14\.dp")

    def test_navigation_stays_above_composer(self):
        buttons = list(calls(self.messages, "ConversationTurnNavigationButton"))
        self.assertEqual(len(buttons), 1, "Expected the messages navigation button")
        self.assertRegex(
            buttons[0],
            r"\.align\s*\(\s*Alignment\.BottomCenter\s*\)\s*"
            r"\.padding\s*\(\s*bottom\s*=\s*12\.dp\s*\+\s*bottomInset\s*,?\s*\)",
        )

    def test_composer_surround_is_transparent(self):
        bar_start = self.source.index("private fun AgentChatBottomBar(")
        bar = self.source[bar_start:bar_start + 4000]
        self.assertNotIn("colorScheme.surface)", bar.split("AgentChatInputBar(", 1)[0])
        self.assertNotIn("textureBlur", self.source)

    def test_bottom_follow_retains_effective_viewport_formula(self):
        # The viewport already ends at the composer top edge; subtracting the list's
        # own bottom padding keeps the follow target at the last text line.
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
