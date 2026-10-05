"""No-SDK wiring/parameter and strict divider contracts for the restored menu.

Actual attached-node touches, persistence and live setting changes are exercised by
TopBarOverflowMenuInteractiveModeTest; these checks do not substitute for Compose.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"


def read(path):
    return (SRC / path).read_text(encoding="utf-8")


def balanced(source, start):
    """Extract one balanced Kotlin argument list or block, ignoring string literals."""
    opening = source[start]
    closing = {"(": ")", "{": "}"}[opening]
    depth = 0
    quoted = False
    escaped = False
    for index in range(start, len(source)):
        char = source[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError("Unclosed Kotlin block or call")


def arguments(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*\(", source)
    if not match:
        raise AssertionError(f"Missing {name} call")
    return balanced(source, match.end() - 1)


def callback(source, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*\{", source)
    if not match:
        raise AssertionError(f"Missing {name} callback")
    return balanced(source, match.end() - 1)


def compact(source):
    return re.sub(r"\s+", "", source)


class TopBarInteractiveModeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.menu = read("ui/app/TopBarOverflowMenu.kt")
        popup = cls.menu.index("EtaDropdownMenu(")
        cls.content = cls.menu[popup:cls.menu.index("ConversationTokenUsageDialog(", popup)]
        cls.entries = []
        cls.sequence = []
        for match in re.finditer(r"\b(DropdownMenuItem|MenuSectionDivider)\(", cls.content):
            if match.group(1) == "MenuSectionDivider":
                cls.sequence.append("divider")
            else:
                entry = balanced(cls.content, match.end() - 1)
                cls.entries.append(entry)
                label = re.search(r"text\s*=\s*\{\s*Text\(stringResource\(R\.string\.(\w+)\)\)", entry)
                if label:
                    cls.sequence.append(label.group(1))
                elif "text = { Text(kimiWebLabel) }" in entry:
                    cls.sequence.append("kimiWebLabel")
                else:
                    raise AssertionError("Unexpected menu row label")
        interactive = [entry for entry in cls.entries if "R.string.action_interactive_mode" in entry]
        if len(interactive) != 1:
            raise AssertionError("Expected exactly one interactive-mode menu row")
        cls.interactive = interactive[0]

    def test_exact_menu_order_restores_only_the_two_missing_group_lines(self):
        self.assertEqual([
            "action_new_conversation", "action_search_history",
            "divider", "action_interactive_mode", "divider",
            "action_open_terminal", "action_open_browser", "action_browse_workspace_files",
            "action_sub_agent_status", "kimiWebLabel", "divider", "action_token_usage",
            "action_compress_conversation", "action_auto_compress_context",
            "divider", "capability_kimi_stop",
        ], self.sequence)
        stop_at = self.content.index("if (canStopKimiWeb)")
        stop = balanced(self.content, self.content.index("{", stop_at))
        self.assertEqual(1, stop.count("MenuSectionDivider()"))
        self.assertIn("R.string.capability_kimi_stop", stop)
        self.assertEqual(3, self.content[:stop_at].count("MenuSectionDivider()"))
        self.assertEqual(4, self.content.count("MenuSectionDivider()"))

    def test_divider_uses_the_existing_helper_and_exact_original_style(self):
        helper_at = self.menu.index("private fun MenuSectionDivider()")
        helper = balanced(self.menu, self.menu.index("{", helper_at))
        self.assertEqual(compact("""{
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                thickness = 1.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
            )
        }"""), compact(helper))
        self.assertEqual(1, self.menu.count("HorizontalDivider("))

    def test_menu_observes_the_real_preference_without_initialization_writes(self):
        self.assertIn("val interactiveModePreference = remember { InteractiveModePreference() }", self.menu)
        self.assertIn("val interactiveModeEnabled by rememberInteractiveModeEnabled(interactiveModePreference)", self.menu)
        self.assertEqual(2, self.menu.count("interactiveModePreference.setEnabled("))
        self.assertEqual(2, self.interactive.count("interactiveModePreference.setEnabled("))
        for forbidden in ("Prefs.putBoolean", "AgentTaskSurface.save", "ask_user", "LaunchedEffect", "SideEffect"):
            self.assertNotIn(forbidden, self.menu)

    def test_row_and_switch_are_both_clickable_without_dismissing_the_popup(self):
        self.assertIn('modifier = CompactMenuItemModifier.testTag("top-bar-interactive-mode-row")', self.interactive)
        self.assertIn("contentPadding = CompactMenuItemPadding", self.interactive)
        self.assertIn("imageVector = Icons.Filled.TouchApp", self.interactive)
        self.assertIn("modifier = Modifier.size(TopBarMenuIconSize)", self.interactive)
        switch = arguments(self.interactive, "Switch")
        self.assertIn("checked = interactiveModeEnabled", switch)
        self.assertIn("interactiveModePreference.setEnabled(enabled)", callback(switch, "onCheckedChange"))
        self.assertIn("onCheckedChange = { enabled ->", switch)
        self.assertIn('Modifier.scale(0.72f).testTag("top-bar-interactive-mode-switch")', switch)
        click = callback(self.interactive, "onClick")
        self.assertIn("interactiveModePreference.setEnabled(!interactiveModeEnabled)", click)
        self.assertNotIn("menuState.dismiss", self.interactive)
        self.assertEqual(2, self.interactive.count("TouchHaptics.click(view)"))
        self.assertIn("private val CompactMenuItemModifier = Modifier.height(40.dp)", self.menu)
        self.assertIn("private val CompactMenuItemPadding = PaddingValues(horizontal = 12.dp)", self.menu)

    def test_preference_and_observer_share_execution_position_storage(self):
        preference = read("config/InteractiveModePreference.kt")
        state = read("ui/components/InteractiveModePreferenceState.kt")
        self.assertIn("private val preferences: SharedPreferences? = Prefs.localAgentPreferences()", preference)
        self.assertIn("val enabled: Boolean get() = AgentTaskSurface.stored() == AgentTaskSurfaceMode.ASK", preference)
        self.assertIn("AgentTaskSurface.save(if (enabled) AgentTaskSurfaceMode.ASK else AgentTaskSurfaceMode.FOREGROUND)", preference)
        self.assertIn("key == null || key == AgentTaskSurface.PREF_KEY", preference)
        self.assertNotIn("putBoolean", preference)
        for source in (self.menu, preference, state):
            self.assertNotIn('"interactive_mode"', source)
        self.assertIn("remember(preference) { mutableStateOf(preference.enabled) }", state)
        self.assertIn("DisposableEffect(preference)", state)
        self.assertIn("preference.observe { enabled.value = it }", state)
        self.assertIn("onDispose { observation.close() }", state)

    def test_menu_call_parameters_keep_existing_actions_and_avoid_lifted_toggle_state(self):
        declaration = arguments(self.menu, "TopBarOverflowMenu")
        caller = arguments(read("ui/app/AgentAppShell.kt"), "TopBarOverflowMenu")
        for name in ("onNewConversation", "onOpenTerminal", "onLaunchKimiWeb", "kimiWebLabel",
                     "canStopKimiWeb", "onStopKimiWeb", "onRefreshKimiWeb", "onOpenBrowser",
                     "onOpenWorkspace", "autoCompressEnabled", "isCompressingContext",
                     "onToggleAutoCompress", "onCompressConversation", "onSearchHistory",
                     "onOpenHistoryHit", "tokenUsage", "subAgentStatuses"):
            with self.subTest(parameter=name):
                self.assertRegex(declaration, r"\b" + name + r"\s*:")
                self.assertIn(f"{name} = {name}", caller)
        self.assertNotIn("interactiveMode", declaration)
        self.assertNotIn("interactiveMode", caller)
        auto = next(entry for entry in self.entries if "R.string.action_auto_compress_context" in entry)
        self.assertIn("checked = autoCompressEnabled", arguments(auto, "Switch"))
        self.assertIn("onToggleAutoCompress(!autoCompressEnabled)", callback(auto, "onClick"))


if __name__ == "__main__":
    unittest.main()
