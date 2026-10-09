from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/ui'

class SettingsJankHotPathContract(unittest.TestCase):
    def test_live_run_does_not_resolve_every_body_owner_before_notice_check(self):
        source = (ROOT / 'model/AgentTerminalMessageOrder.kt').read_text()
        self.assertLess(source.index('if (runs.isEmpty()) return messages'), source.index('val messageOwners = messages.map'))
        self.assertIn('if (message !is SystemNoticeMessageUi || !message.code.isTerminal()) return@forEachIndexed', source)

    def test_usage_replaces_one_immutable_slot_and_preserves_holder_fallback(self):
        source = (ROOT / 'app/AgentAppState.kt').read_text().split('private fun updateAssistantUsage(', 1)[1].split('private fun revokeContextActual', 1)[0]
        self.assertIn('if (usage.isEmpty) return', source)
        self.assertIn('if (isStaleUsageAfterCompact(runId, round)) return', source)
        self.assertIn('if (targetIndex < 0)', source)
        self.assertIn('messages + AgentMessageUi(', source)
        self.assertIn('messages.incrementalSnapshot().replacing(targetIndex, target.copy(usage = usage))', source)
        self.assertNotIn('mapIndexed', source)

    def test_measurement_slots_are_opt_in_without_changing_effect_chain(self):
        settings = (ROOT / 'SettingsScreen.kt').read_text()
        scaffold = (ROOT / 'components/MiuixScaffoldPage.kt').read_text().split('fun MiuixScaffold(', 1)[0]
        labels = (ROOT / 'components/BoundedStreamDiagnostics.kt').read_text()
        for name, stage in [('topBarModifier', 'settings.topbar.measure'), ('listModifier', 'settings.lazy.measure')]:
            self.assertIn(f'{name}: Modifier = Modifier', scaffold)
            self.assertIn(f'{name} = Modifier.streamDiagnosticMeasure("{stage}")', settings)
            self.assertIn(f'"{stage}"', labels)
        self.assertIn('.streamDiagnosticPlacement("settings.lazy.place").streamDiagnosticDraw("settings.lazy.draw")', settings)
        manage=(ROOT/'screens/chat/ManageChatsScreen.kt').read_text()
        drawer=(ROOT/'components/ConversationSidePaneScaffold.kt').read_text()
        for source,prefix in ((manage,'manage.lazy'),(drawer,'drawer.lazy')):
            compact=''.join(source.split())
            self.assertIn(f'.streamDiagnosticMeasure("{prefix}.measure").streamDiagnosticPlacement("{prefix}.place").streamDiagnosticDraw("{prefix}.draw")',compact)
            for suffix in ('measure','place','draw'): self.assertIn(f'"{prefix}.{suffix}"',labels)
        self.assertIn('modifier = topBarModifier', scaffold)
        self.assertIn('modifier = listModifier', scaffold)
        chain = ['.fillMaxSize()', '.horizontalCutoutPadding()', '.captureForTopBar(backdrop)', '.scrollEndHaptic()', '.overScrollVertical()', '.nestedScroll(scrollBehavior.nestedScrollConnection)']
        lazy = scaffold.split('LazyColumn(', 1)[1]
        self.assertEqual(sorted(lazy.index(item) for item in chain), [lazy.index(item) for item in chain])
        backdrop = (ROOT / 'components/TopBarBackdrop.kt').read_text()
        capture = backdrop.split('internal fun Modifier.captureForTopBar(', 1)[1].split('@Composable', 1)[0]
        self.assertNotIn('isScrollInProgress', capture)
        self.assertIn('layerBackdrop(backdrop)', capture)


    def test_settings_collects_remembered_store_flows(self):
        settings = (ROOT / 'SettingsScreen.kt').read_text()
        self.assertIn('remember { SettingsDataStore.settingsFlow() }', settings)
        self.assertIn('remember { ProviderRepository.providersFlow() }', settings)
        self.assertIn('remember { RuntimeConfigRepository.selectedProviderIdFlow() }', settings)
        self.assertIn('remember { RuntimeConfigRepository.selectedModelIdFlow() }', settings)
        self.assertNotIn('val appSettings by SettingsDataStore.settingsFlow().collectAsState', settings)

    def test_inactive_chat_route_freezes_message_snapshot(self):
        helper = (ROOT / 'components/ChatUiActive.kt').read_text()
        self.assertIn('staticCompositionLocalOf { true }', helper)
        self.assertIn('val LocalChatUiActive', helper)
        self.assertIn('val LocalChatRouteCovered = staticCompositionLocalOf { false }', helper)
        root = (ROOT / 'app/AgentAppRoot.kt').read_text()
        # 半遮住时聊天还在组合里，继续用实时消息；完全盖住后导航移出组合。
        self.assertIn('LocalChatUiActive provides true', root)
        self.assertIn('LocalChatRouteCovered provides (backStack.lastOrNull() != route)', root)
        self.assertNotIn('if (isCurrentRoute) {\n                    AgentHomeScreen', root)
        body = (ROOT / 'components/AgentChatBody.kt').read_text()
        start = body.index('internal fun AgentChatBody(')
        end = body.index('internal fun AgentChatScaffold(')
        host = body[start:end]
        self.assertIn('val chatUiActive = LocalChatUiActive.current', host)
        self.assertIn('remember(chatUiActive)', host)
        self.assertIn('visibleMessagesCache.project(uiMessages', host)
        self.assertIn('isStreaming = uiStreaming', host)
        self.assertIn('isPaused = uiPaused', host)
        self.assertIn('LaunchedEffect(messages, isStreaming)', host)
        self.assertIn('enabled = chatUiActive && !isPaused', host)

    def test_chat_keeps_live_output_while_navigation_animates(self):
        helper = (ROOT / 'components/ChatUiActive.kt').read_text()
        self.assertIn('val LocalChatNavigationInProgress = staticCompositionLocalOf { false }', helper)
        root = (ROOT / 'app/AgentAppRoot.kt').read_text()
        self.assertIn('LocalChatNavigationInProgress provides navigationInProgress', root)
        self.assertIn('transition = navigationTransition,', root)
        transition = (ROOT / 'app/ChatNavigationTransition.kt').read_text()
        # 手势与回弹、普通入栈出栈都算动画进行中
        self.assertIn('scope.gesture != null || scope.settle != null', transition)
        # 视觉与默认预设一致，不新增位移/缩放/透明度
        self.assertIn('translationX = (if (rtl) 1f else -1f) * coverProgress(d) * width * 0.25f', transition)
        self.assertIn('alpha = 1f - 0.1f * coverProgress(d)', transition)
        self.assertIn('navGraphicsTransition(opaqueDepth = 1f)', transition)
        item = (ROOT / 'components/ChatMessageItem.kt').read_text()
        self.assertIn('val navigationInProgressNow = rememberUpdatedState(LocalChatNavigationInProgress.current)', item)
        self.assertIn('if (routeCoveredNow.value || navigationInProgressNow.value) {', item)
        self.assertIn('val revealClockAllowed = !routeCoveredNow.value && !navigationInProgressNow.value', item)
        # 动画期间不得走“追平代替推进”的分支
        self.assertIn('!revealClockAllowed && !isPaused -> revealCoordinator.pauseAnimationsAndCatchUp()', item)

    def test_streaming_body_skips_selection_registry(self):
        container = (ROOT / 'haptics/HapticSelectionContainer.kt').read_text()
        self.assertIn('selectionEnabled: Boolean = true', container)
        self.assertIn('if (!selectionEnabled) {', container)
        item = (ROOT / 'components/ChatMessageItem.kt').read_text()
        self.assertIn('selectionEnabled = !message.isStreaming &&\n                    (!keepStreamingMarkdown || streamingRevealComplete),', item)
