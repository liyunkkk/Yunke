import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
UI = ROOT / "src/main/kotlin/io/github/mangi/eta/ui"

class VirtualDisplaySettingsUiTest(unittest.TestCase):
    def test_recovery_is_only_nested_under_task_preferences(self):
        settings = (UI / "SettingsScreen.kt").read_text()
        task = (UI / "AgentTaskPreferenceScreen.kt").read_text()
        self.assertNotIn("VirtualDisplayRecoveryPreference", settings)
        self.assertNotIn("vd_recovery_title", settings)
        self.assertIn("if (taskBackendInstalled == true)", settings)
        self.assertNotIn("onOpenRecovery", task)
        self.assertIn("VirtualDisplayRecoveryControls(", task)
        self.assertIn("moduleInstalled != true", task)

    def test_task_preference_page_is_material3_with_radio_group(self):
        task = (UI / "AgentTaskPreferenceScreen.kt").read_text()
        for text in (
            "import androidx.compose.material3.Scaffold",
            "import androidx.compose.material3.TopAppBar",
            "import androidx.compose.material3.ListItem",
            "import androidx.compose.material3.RadioButton",
            "Scaffold(",
            "TopAppBar(",
            "Icons.AutoMirrored.Rounded.ArrowBack",
            "selectableGroup()",
            "Modifier.selectable(",
            "role = Role.RadioButton",
            "verticalScroll(",
        ):
            self.assertIn(text, task)
        self.assertNotIn("MiuixScaffoldPage", task)
        # 选择语义不变：可选项仍由 allowsPersist 决定，整行点击仍写同一份持久化值。
        self.assertIn("AgentTaskSurface.allowsPersist(mode)", task)
        self.assertIn("AgentTaskSurface.save(mode)", task)
        self.assertIn("AgentTaskSurface.stored()", task)

    def test_recovery_is_inline_controls_not_separate_page(self):
        page = (UI / "VirtualDisplayRecoveryScreen.kt").read_text()
        self.assertNotIn("Scaffold(", page)
        self.assertNotIn("TopAppBar(", page)
        self.assertIn("Row(", page)
        self.assertIn("FlowRow(", page)
        # 内联动作只剩刷新与手动收尾，各一个入口；按钮文字不允许折行。
        self.assertEqual(2, page.count("TouchHaptics.click(view)"))
        self.assertEqual(2, page.count("maxLines = 1"))
        # 跨设备网页预览入口已移除：手机上改用只读镜像页与悬浮小窗。
        self.assertNotIn("VirtualDisplayWebPreview.openWithManualClose(context)", page)
        self.assertNotIn("VirtualDisplayWebPreview.open(context)", page)
        self.assertNotIn("vd_preview_control_open", page)
        self.assertNotIn("WindowDialog", page)
        self.assertNotIn("AlertDialog", page)
        self.assertIn("LaunchedEffect(Unit) { refresh() }", page)
        self.assertIn('snapshot.optBoolean("recoverable")', page)
        self.assertIn("VirtualDisplaySession::recoverAndFinishManually", page)
        self.assertNotIn("VirtualDisplayWebPreview.stop()", page)
        for action in ("vd_recovery_refresh", "vd_recovery_action"):
            self.assertIn("R.string." + action, page)
        self.assertIn('snapshot?.optBoolean("busy")', page)
        self.assertIn("busy -> R.string.vd_recovery_busy", page)
        # 预览入口删除后，FlowRow 里只剩刷新与手动收尾两个动作。
        actions_block = page.split("FlowRow(", 1)[1]
        self.assertIn("enabled = installed == true && !working,", actions_block)
        self.assertNotIn("R.string.vd_preview_open", actions_block)
        self.assertNotIn("R.string.vd_preview_revoke", actions_block)
        root = (UI / "app/AgentAppRoot.kt").read_text()
        self.assertIn("entry<AppRoute.VirtualDisplayRecovery>", root)
        self.assertNotIn("VirtualDisplayRecoveryScreen(onBack = ::popRoute)", root)
        self.assertIn("onBack = ::popRoute,\n                    onRecoveryWorkingChanged = { taskRecoveryWorking = it },", root)

    def test_task_preference_swipe_back_follows_setting_except_during_recovery(self):
        root = (UI / "app/AgentAppRoot.kt").read_text()
        self.assertIn("if (taskRecoveryWorking) NavSwipeDirection.None else swipeDismiss", root)
        self.assertIn("entry<AppRoute.AgentTaskPreference>(swipeDismiss = taskPreferenceSwipeDismiss)", root)
        self.assertIn("entry<AppRoute.VirtualDisplayRecovery>(swipeDismiss = taskPreferenceSwipeDismiss)", root)
        self.assertNotIn("entry<AppRoute.AgentTaskPreference>(swipeDismiss = null)", root)

    def test_installation_is_rechecked_on_resume_not_live_backend_status(self):
        source = (UI / "TaskBackendInstallation.kt").read_text()
        self.assertIn("Lifecycle.State.RESUMED", source)
        self.assertIn("value = null", source)
        self.assertIn("AgentTaskSurface.moduleInstalled()", source)
        self.assertNotIn("inspect_virtual_backend", source)
        self.assertIn("awaitCancellation()", source)

    def test_leaving_page_does_not_touch_pairing_and_uninstall_revokes(self):
        page = (UI / "VirtualDisplayRecoveryScreen.kt").read_text()
        cleanup = page.split("DisposableEffect(Unit) {", 1)[1].split("val snapshot", 1)[0]
        self.assertIn("onDispose {", cleanup)
        self.assertIn("onWorkingChanged(false)", cleanup)
        self.assertNotIn("VirtualDisplayWebPreview.", cleanup)
        self.assertNotIn("VirtualDisplayWebPreview.stop()", page)
        # 后端被移除时仍会自动撤销历史配对；页面本身不再暴露预览/撤销按钮。
        self.assertIn("if (installed == false && !working)", page)
        self.assertIn("VirtualDisplayWebPreview.revoke(context)", page)
        self.assertNotIn("R.string.vd_preview_open", page)
        self.assertNotIn("R.string.vd_preview_revoke", page)
        self.assertNotIn("Lifecycle.Event.ON_PAUSE", page)
        self.assertNotIn("Lifecycle.Event.ON_STOP", page)

if __name__ == "__main__":
    unittest.main()
