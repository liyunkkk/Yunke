"""Live Update（提升式常驻通知）契约。

锁三件事：
1. 清单里必须声明 POST_PROMOTED_NOTIFICATIONS——Android 16+ 缺这条权限时系统会静默拒绝提升；
2. 提升态（Agent run 在跑 + Android 16+）一个 miui.focus.* / miui.enableFloat 都不写，
   否则 HyperOS 的 FocusNotifPreHandler 会接管渲染、压掉原生 Live Update；
3. 非提升态仍写全部 8 个 MIUI 键，且样式规划与改动前一致（有展开正文才设 BigTextStyle）。
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SERVICE = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentExecutionService.kt").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

MIUI_KEYS = [
    "miui.focusNotification",
    "miui.focusNotification.subTitle",
    "miui.focus.isFocus",
    "miui.focus.ticker",
    "miui.enableFloat",
    "miui.focus.param",
    "miui.focus.pics",
    "miui.focus.actions",
]


def squeeze(text: str) -> str:
    return re.sub(r"\s+", " ", text)


class PromotedNotificationContract(unittest.TestCase):
    def test_manifest_declares_post_promoted_notifications(self):
        self.assertIn(
            '<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS" />',
            MANIFEST,
        )

    def test_promoted_state_plans_no_miui_extras(self):
        source = squeeze(SERVICE)
        # 计划函数：提升态返回空表，非提升态返回完整键表。
        self.assertIn("fun miuiFocusKeys(promoted: Boolean): List<String> = if (promoted) emptyList() else MIUI_FOCUS_KEYS", source)
        # 通知构建处必须按 promote 取计划，不能无条件写 MIUI 键。
        self.assertIn("val miuiFocusKeys = ExecutionNotificationPlan.miuiFocusKeys(promote)", source)
        self.assertIn("if (miuiFocusKeys.isNotEmpty()) { applyMiuiFocusExtras(builder, miuiFocusKeys, appTitle, actionText) }", source)

    def test_every_miui_key_is_written_through_the_plan(self):
        source = squeeze(SERVICE)
        for key in MIUI_KEYS:
            self.assertIn(f'internal const val', source)
            self.assertIn(f'"{key}"', source)
        # 直接写字符串键的旧写法必须消失（否则会绕过提升态闸门）。
        self.assertNotIn('builder.extras.putBoolean("miui.focusNotification", true)', source)
        self.assertNotIn('builder.extras.putString("miui.focus.ticker", actionText)', source)
        self.assertNotIn('builder.extras.putBundle("miui.focus.pics", focusPics())', source)

    def test_promoted_state_requests_ongoing_promotion(self):
        source = squeeze(SERVICE)
        self.assertIn('PROMOTED_ONGOING_EXTRA = "android.requestPromotedOngoing"', source)
        self.assertIn("builder.extras.putBoolean(PROMOTED_ONGOING_EXTRA, true)", source)
        # 提升只在 Android 16+ 且运行态发生，并且有显式版本闸门。
        self.assertIn("AgentExecutionState.shouldPromoteToLiveUpdate(state, Build.VERSION.SDK_INT)", source)
        self.assertIn("if (promote && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)", source)

    def test_no_progress_style_anywhere(self):
        # 无进度的 ProgressStyle 会在提升卡下方渲染空进度白条（注释里提到这个名字是允许的）。
        self.assertNotIn("Notification.ProgressStyle", SERVICE)
        self.assertNotIn("setStyle(Notification.ProgressStyle", SERVICE)
        self.assertNotIn("setProgress(", SERVICE)

    def test_big_text_style_is_the_only_style(self):
        self.assertIn("Notification.BigTextStyle()", SERVICE)
        self.assertIn("ExecutionNotificationPlan.style(promote, expanded != null)", SERVICE)


if __name__ == "__main__":
    unittest.main()
