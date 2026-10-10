package io.github.mangi.eta.agent.runtime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.ui.MainActivity

class AgentNotificationTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        try {
            val source = intent.getStringExtra(EXTRA_SOURCE)
            // 只按来源判定：SOURCE_OVERLAY 表示这次任务就是从语音浮窗里发起的，才回到浮窗。
            //
            // 这里曾经额外判过 EtaAssistantOverlayService.isServiceActive()，那是个错误启发式：
            // activeService 只在 Service.onDestroy 里清空，而移除浮窗窗口并不会销毁 Service，
            // 于是用户只要用过一次语音浮窗，之后每次点通知（哪怕 source 明确是 main）都会被
            // 劫持进语音浮窗；浮窗在没有 activeRunId 时会清空上下文并直接进入语音录音态，
            // 表现就是"点完成通知跳到了语音态，而不是任务界面"。
            // 从浮窗发起的任务已经会带 SOURCE_OVERLAY，无需再靠 Service 存活与否来兜底。
            if (source == SOURCE_OVERLAY) {
                EtaAssistantOverlayService.show(this)
            } else {
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(mainIntent)
            }
        } finally {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_SOURCE = "io.github.mangi.eta.extra.NOTIFICATION_SOURCE"
        const val SOURCE_OVERLAY = "overlay"
        const val SOURCE_MAIN = "main"
    }
}
