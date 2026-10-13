package io.github.mangi.eta.agent.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 移植自 lejw0925/Eta-vivo: agent/display/VirtualScreenNotification.kt
 * Yunke 适配：
 * - vivo 的 R.string.virtual_screen_notification_* 字符串资源在 Yunke 不存在，
 *   改用中文字符串字面量（后续可在 res/values 中补正式资源）；
 * - VirtualScreenViewerActivity（vivo 查看器）未移植，不再设置 contentIntent（TODO）；
 * - 日志改用 Yunke 的 core.AndroidAgentLogger。
 */
internal object VirtualScreenNotification {
    private const val CHANNEL = "eta_virtual_screen"
    private const val ID = 1110

    fun show(context: Context, sessionId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "虚拟屏任务", NotificationManager.IMPORTANCE_LOW,
            ))
            // TODO: 移植 VirtualScreenViewerActivity 后恢复点击跳转
            manager.notify(sessionId, ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("虚拟屏任务运行中")
                .setContentText("后台虚拟屏会话正在执行任务")
                .setOngoing(true).setOnlyAlertOnce(true).build())
        }.onFailure { AndroidAgentLogger.warn("Virtual screen notification failed: ${it.javaClass.simpleName}") }
    }

    fun cancel(context: Context, sessionId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(sessionId, ID)
    }
}
