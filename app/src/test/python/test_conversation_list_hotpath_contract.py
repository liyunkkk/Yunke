from pathlib import Path
import unittest

UI = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/ui'


class ConversationListHotpathContract(unittest.TestCase):
    def test_drawer_focus_observation_is_local_to_search_row(self):
        source = (UI / 'components/ConversationSidePaneScaffold.kt').read_text()
        panel = source.split('private fun ConversationPanePanel(', 1)[1].split('private fun PaneSearchRow(', 1)[0]
        search = source.split('private fun PaneSearchRow(', 1)[1].split('private fun PaneDrawerActions(', 1)[0]
        self.assertIn('enableSearchFocus: () -> Boolean', panel)
        self.assertNotIn('enableSearchFocus()', panel)
        self.assertIn('val searchFocusEnabled = enableSearchFocus()', search)
        self.assertIn('enabled = searchFocusEnabled && searchActive', search)
        self.assertIn('drawerContent = {\n                ConversationPanePanel(', source)
        self.assertNotIn('drawerState.currentOffset', source)

    def test_manage_observes_drag_outside_composition_and_skips_occluded_draw(self):
        source = (UI / 'screens/chat/ManageChatsScreen.kt').read_text()
        self.assertNotIn('LaunchedEffect(offsetX', source)
        self.assertIn('snapshotFlow { offsetX <= -deleteDistance }', source)
        self.assertIn('.offset { IntOffset(offsetX.roundToInt(), 0) }', source)
        self.assertIn('.drawWithContent { if (offsetX < 0f) drawContent() }', source)
        self.assertIn('conversations.associate { it.id to it.manageChatListItem() }', source)
        self.assertIn('remember(nextOrder, rowItemsById)', source)
        self.assertIn('fadeOutSpec = tween(180)', source)
        self.assertIn('tween(durationMillis = 280, easing = FastOutSlowInEasing)', source)

    def test_provider_flow_factories_are_remembered(self):
        source = (UI / 'pages/providers/ModelProviderListScreen.kt').read_text()
        self.assertIn('remember { ProviderRepository.providersFlow() }', source)
        self.assertIn('remember { RuntimeConfigRepository.selectedProviderIdFlow() }', source)
