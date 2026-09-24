package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null) {
            ensureChannels(this, manager)
        }
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundActive = true
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTasks()
        } else {
            ensureForeground()
            refreshNotification()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val openIntent = Intent(this, AgentNotificationTrampolineActivity::class.java).apply {
            putExtra(AgentNotificationTrampolineActivity.EXTRA_SOURCE, currentSource)
        }
        val open = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = executionState
        val appTitle = state.subtitle ?: getString(R.string.app_name)
        val actionText = state.title ?: getString(R.string.execution_phase_thinking)
        val detailText = state.detail ?: if (leases.executingSessionCount() == 0) {
            getString(R.string.execution_summary_idle)
        } else {
            getString(R.string.execution_summary, leases.executingSessionCount())
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appTitle)
            .setSubText(actionText)
            .setContentText(detailText)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())

        if (state.showChronometer && state.startedAtElapsedRealtime > 0L) {
            val baseTimeMillis = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - state.startedAtElapsedRealtime)
            builder.setWhen(baseTimeMillis)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }

        val expanded = state.expandedSnippet?.takeIf { it.isNotBlank() }
        if (expanded != null) {
            builder.setStyle(
                Notification.BigTextStyle()
                    .setBigContentTitle(appTitle)
                    .bigText(expanded)
                    .setSummaryText(actionText),
            )
        }

        // 小米焦点通知胶囊（灵动岛）扩展，两套协议同时写以保证新旧系统都能上岛。
        // HyperOS 3 / MIUI 14-15：读 miui.focusNotification 与 miui.focusNotification.subTitle。
        // HyperOS 4+：上述旧 key 已被系统整体移除；SystemUI 的
        //   FocusUtils.isFocusNotification(Notification) 只认下面两条之一：
        //     extras["miui.focus.isFocus"] 为 true，或 extras["miui.focus.rv"] 是 RemoteViews。
        // 判定结果写入 ExpandedNotification.mIsFocusNotification，
        // 由 FocusCoordinator 的 isInSection / shouldPromoteToTopLevel 决定是否进焦点分区（上岛）。
        // miui.enableFloat 在 HyperOS 3/4 的 ExpandedNotification.canFloat() 中均被读取，
        // 保持 false 以维持既有行为（不弹浮窗）。
        builder.extras.putBoolean("miui.focusNotification", true)
        builder.extras.putString("miui.focusNotification.subTitle", actionText)
        builder.extras.putBoolean("miui.focus.isFocus", true)
        builder.extras.putString("miui.focus.ticker", actionText)
        builder.extras.putBoolean("miui.enableFloat", false)

        return builder.build()
    }

    companion object {
        const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null

        @Volatile var backupMaintenance: Boolean = false
            private set

        @Synchronized fun beginBackupMaintenance() {
            check(!backupMaintenance && leases.count() == 0) { "请先停止 Agent 任务并关闭终端会话，再备份或恢复" }
            backupMaintenance = true
        }

        @Synchronized fun endBackupMaintenance() { backupMaintenance = false }

        const val CHANNEL_COMPLETED = "eta_completed"
        private const val COMPLETION_NOTIFICATION_ID_BASE = 20000
        @Volatile private var executionState = AgentExecutionState()
        @Volatile private var currentSource: String = AgentNotificationTrampolineActivity.SOURCE_MAIN

        fun ensureChannels(context: Context, manager: NotificationManager) {
            val executionChannel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.execution_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED,
                context.getString(R.string.execution_completed_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            manager.createNotificationChannel(executionChannel)
            manager.createNotificationChannel(completedChannel)
        }

        fun postCompletionNotification(
            context: Context,
            runId: String,
            title: String,
            content: String,
            isError: Boolean = false,
            source: String = AgentNotificationTrampolineActivity.SOURCE_MAIN,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannels(context, manager)
            val intent = Intent(context, AgentNotificationTrampolineActivity::class.java).apply {
                putExtra(AgentNotificationTrampolineActivity.EXTRA_SOURCE, source)
            }
            val open = PendingIntent.getActivity(
                context,
                runId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val displayTitle = if (isError) {
                context.getString(R.string.execution_failed_default)
            } else {
                title.ifBlank { context.getString(R.string.execution_completed_default) }
            }
            val displayContent = content.ifBlank {
                if (isError) "" else context.getString(R.string.execution_completed_default)
            }
            val builder = Notification.Builder(context, CHANNEL_COMPLETED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(displayTitle)
                .setContentText(displayContent)
                .setStyle(Notification.BigTextStyle().bigText(displayContent))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setShowWhen(true)
            val notificationId = COMPLETION_NOTIFICATION_ID_BASE + (runId.hashCode() and 0x7FFF)
            manager.notify(notificationId, builder.build())
        }

        fun updateExecutionState(state: AgentExecutionState) {
            executionState = state
            mainHandler.post { instance?.refreshNotification() }
        }

        fun resetExecutionState() {
            executionState = AgentExecutionState()
            mainHandler.post { instance?.refreshNotification() }
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        @Synchronized fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            source: String = AgentNotificationTrampolineActivity.SOURCE_MAIN,
            countsAsExecutingSession: Boolean = true,
            onStop: () -> Unit,
        ): Boolean {
            currentSource = source
            if (backupMaintenance || instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, countsAsExecutingSession, onStop)) return true
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            if (leases.count() == 0) {
                executionState = AgentExecutionState()
                currentSource = AgentNotificationTrampolineActivity.SOURCE_MAIN
            }
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
