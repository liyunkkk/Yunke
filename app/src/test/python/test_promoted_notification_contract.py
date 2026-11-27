"""DSH Go 同款 Live Update（提升式常驻通知）契约。

锁五件事：
1. 清单里必须声明 POST_PROMOTED_NOTIFICATIONS；
2. Android 16+ 提升态走原生 Live Update（ProgressStyle 不定进度条 + 实时计时胶囊），
   一个 miui.* 都不写，避免劫持 HyperOS 原生液态玻璃上岛渲染；
3. 任何状态都不添加操作按钮（无 addAction、无 textButton、无自定义 RemoteViews 按钮卡）；
4. 通知构建与 NotificationManager.notify() 运行在后台 HandlerThread，不阻塞主线程切后台动画；
5. 非提升态（Android <= 15）仅经 canShowFocus 校验后附最简 protocol 1 焦点参数，零光边零撞色。
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SERVICE = (ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentExecutionService.kt").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")


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
        self.assertIn("fun miuiFocusKeys(promoted: Boolean): List<String> = if (promoted) emptyList() else MIUI_FOCUS_KEYS", source)
        self.assertIn("val miuiFocusKeys = ExecutionNotificationPlan.miuiFocusKeys(promote)", source)
        self.assertIn("if (miuiFocusKeys.isNotEmpty() && allowFocusQuery)", source)

    def test_promoted_state_uses_indeterminate_progress_style_and_requests_promotion(self):
        source = squeeze(SERVICE)
        self.assertIn('PROMOTED_ONGOING_EXTRA = "android.requestPromotedOngoing"', source)
        self.assertIn("builder.extras.putBoolean(PROMOTED_ONGOING_EXTRA, true)", source)
        self.assertIn("AgentExecutionState.shouldPromoteToLiveUpdate(state, Build.VERSION.SDK_INT)", source)
        self.assertIn("if (promote && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)", source)
        self.assertIn("Notification.ProgressStyle()", source)
        self.assertIn(".setProgressIndeterminate(true)", source)
        self.assertIn("Notification.ProgressStyle.Segment(1).setColor(accent)", source)

    def test_zero_buttons_in_every_notification_state(self):
        self.assertNotIn(".addAction(", SERVICE)
        self.assertNotIn('"textButton"', SERVICE)
        self.assertNotIn("miui.focus.actions", SERVICE)
        self.assertNotIn("setCustomContentView(", SERVICE)
        self.assertNotIn("setCustomBigContentView(", SERVICE)

    def test_no_builder_set_color_no_large_icon_no_outer_glow(self):
        # 通知整体不设 setColor（仅进度轨 Segment 着色），不设大图标，不加彩虹外发光。
        self.assertNotIn("builder.setColor(", SERVICE)
        self.assertNotIn(".setColor(BRAND_COLOR)", SERVICE)
        self.assertNotIn("setLargeIcon(", SERVICE)
        self.assertNotIn("outEffectSrc", SERVICE)
        self.assertNotIn("colorSubTitle", SERVICE)

    def test_notification_dispatch_runs_off_main_thread(self):
        source = squeeze(SERVICE)
        self.assertIn('HandlerThread("eta-live-notify")', source)
        self.assertIn("private fun performBackgroundRefresh()", source)
        self.assertIn("isXiaomiFocusAllowed(this)", source)
        self.assertIn('"canShowFocus"', source)

    def test_completion_notification_never_promotes_or_expands(self):
        source = squeeze(SERVICE)
        self.assertIn(".setOngoing(false) .setOnlyAlertOnce(true)", source)
        self.assertIn("builder.extras.putBoolean(PROMOTED_ONGOING_EXTRA, false)", source)
        self.assertIn(
            "fun resetExecutionState() { executionState = AgentExecutionState() publishedExecutionState = null }",
            source,
        )

    def test_completion_body_is_bounded_not_twenty_chars(self):
        source = squeeze(SERVICE)
        self.assertIn("MAX_COMPLETION_BODY_CHARS = 800", source)
        self.assertIn("ExecutionNotificationPlan::completionBody", source)
        for path in (
            "app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppState.kt",
            "app/src/main/kotlin/io/github/mangi/eta/agent/voice/EtaAssistantOverlayService.kt",
        ):
            text = (ROOT / path).read_text(encoding="utf-8")
            self.assertNotIn("result.content.trim().take(200)", text)
            self.assertIn("result.content.trim()", text)

    def test_execution_channel_is_low_importance_and_migrates_legacy_high_channel(self):
        source = squeeze(SERVICE)
        self.assertIn('const val CHANNEL = "eta_execution_v2"', source)
        self.assertIn('manager.deleteNotificationChannel(LEGACY_CHANNEL)', source)
        self.assertIn("NotificationManager.IMPORTANCE_LOW", source)
        self.assertNotIn("NotificationManager.IMPORTANCE_HIGH", source)


if __name__ == "__main__":
    unittest.main()
