package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false
    @Volatile private var lastNotifiedAtMs = 0L
    @Volatile private var cachedOpenKey: Pair<String, String?>? = null
    @Volatile private var cachedOpenIntent: PendingIntent? = null

    private val applyNotificationTask = Runnable { performBackgroundRefresh() }
    private val periodicRepostTask = Runnable {
        publishedExecutionState = null
        scheduleNotificationRefresh(immediate = true)
    }

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
            // startForeground 必须在主线程同步完成；此处不查询跨进程焦点白名单，避免阻塞主线程动画。
            startForeground(
                NOTIFICATION_ID,
                notification(allowFocusQuery = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            foregroundActive = true
            lastNotifiedAtMs = SystemClock.elapsedRealtime()
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
            val justStarted = !foregroundActive
            ensureForeground()
            if (!justStarted || Build.VERSION.SDK_INT < AgentExecutionState.LIVE_UPDATE_MIN_SDK) {
                scheduleNotificationRefresh(immediate = false)
            } else if (executionState.isRunning) {
                notifyHandler.removeCallbacks(periodicRepostTask)
                notifyHandler.postDelayed(periodicRepostTask, LIVE_REFRESH_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        notifyHandler.removeCallbacks(applyNotificationTask)
        notifyHandler.removeCallbacks(periodicRepostTask)
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            scheduleNotificationRefresh(immediate = true)
        }
    }

    /**
     * 对齐 DSH Go `LiveActivity.refresh()`：所有通知构建、焦点白名单查询与 `NotificationManager.notify()`
     * 全部移到独立后台 `HandlerThread("eta-live-notify")`，并保留 600ms 最小间隔节流，
     * 彻底避免任务运行期间划回桌面/切后台时主线程被 Binder IPC 阻塞造成动画卡顿。
     */
    private fun scheduleNotificationRefresh(immediate: Boolean = false) {
        notifyHandler.removeCallbacks(applyNotificationTask)
        if (immediate) {
            notifyHandler.post(applyNotificationTask)
            return
        }
        val waitMs = (lastNotifiedAtMs + MIN_NOTIFY_GAP_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (waitMs == 0L) {
            notifyHandler.post(applyNotificationTask)
        } else {
            notifyHandler.postDelayed(applyNotificationTask, waitMs)
        }
    }

    private fun performBackgroundRefresh() {
        if (leases.closeOwnerIfIdle(owner)) {
            notifyHandler.removeCallbacks(periodicRepostTask)
            mainHandler.post {
                if (instance === this) {
                    foregroundActive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return
        }
        val built = runCatching { notification(allowFocusQuery = true) }.getOrNull() ?: return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, built)
        }
        lastNotifiedAtMs = SystemClock.elapsedRealtime()
        notifyHandler.removeCallbacks(periodicRepostTask)
        if (executionState.isRunning) {
            notifyHandler.postDelayed(periodicRepostTask, LIVE_REFRESH_MS)
        }
    }

    private fun openConversationPendingIntent(): PendingIntent {
        val source = currentSource
        val conversationKey = currentConversationKey?.takeIf(String::isNotBlank)
        val cacheKey = source to conversationKey
        cachedOpenIntent?.takeIf { cachedOpenKey == cacheKey }?.let { return it }
        val openIntent = Intent(this, AgentNotificationTrampolineActivity::class.java).apply {
            putExtra(AgentNotificationTrampolineActivity.EXTRA_SOURCE, source)
            conversationKey?.let { key ->
                putExtra(io.github.mangi.eta.agent.voice.EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, key)
            }
        }
        return PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).also {
            cachedOpenKey = cacheKey
            cachedOpenIntent = it
        }
    }

    private fun progressAccentColor(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { return getColor(android.R.color.system_accent1_500) }
        }
        return DEFAULT_PROGRESS_ACCENT
    }

    private fun notification(allowFocusQuery: Boolean): Notification {
        val open = openConversationPendingIntent()
        val state = if (suppressLiveUpdateFlag) executionState.copy(suppressLiveUpdate = true) else executionState
        val count = leases.count().coerceAtLeast(1)
        val appTitle = state.subtitle?.takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
        val baseStatus = if (state.isRunning) {
            state.cleanStatusLine().ifBlank { getString(R.string.execution_title) }
        } else {
            getString(R.string.execution_summary, count)
        }
        val statusWithCount = if (state.isRunning && count > 1) {
            "$baseStatus · ${count} 个任务"
        } else {
            baseStatus
        }
        val statusLine = if (virtualDisplayActiveFlag) {
            "后台运行中 · $statusWithCount"
        } else {
            statusWithCount
        }

        // 对齐 DSH Go + 用户要求：任何状态都不挂操作按钮（无 addAction），点击卡片直接回到对应对话；
        // 不调用 Notification.Builder.setColor / setLargeIcon，保留 HyperOS 4 液态玻璃原生通透底色与圆角应用图标。
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appTitle)
            .setContentText(statusLine)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (state.isRunning) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(state, open))

        if (state.isRunning) {
            builder.setTimeoutAfter(LIVE_TIMEOUT_MS)
        }

        if (state.showChronometer && state.startedAtElapsedRealtime > 0L) {
            val baseTimeMillis = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - state.startedAtElapsedRealtime)
            builder.setWhen(baseTimeMillis)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }

        val expanded = state.expandedSnippet?.takeIf { it.isNotBlank() }
        val promote = AgentExecutionState.shouldPromoteToLiveUpdate(state, Build.VERSION.SDK_INT)

        when (ExecutionNotificationPlan.style(promote, expanded != null)) {
            ExecutionNotificationStyle.PROGRESS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    applyLiveProgressStyle(builder)
                }
            }
            ExecutionNotificationStyle.BIG_TEXT -> {
                builder.setStyle(
                    Notification.BigTextStyle()
                        .setBigContentTitle(appTitle)
                        .bigText(expanded ?: statusLine),
                )
            }
            ExecutionNotificationStyle.NONE -> Unit
        }

        // Android 16+ 提升态（promote == true）一个 miui.* 键都不写，完全交给原生 Live Update（HyperOS 3.1+/4 原生上岛）；
        // 仅非提升态且 SystemUI 显式放行 canShowFocus 时，才附带 DSH Go 同款极简 protocol 1 焦点参数。
        val miuiFocusKeys = ExecutionNotificationPlan.miuiFocusKeys(promote)
        if (miuiFocusKeys.isNotEmpty() && allowFocusQuery) {
            applyXiaomiFocusExtras(
                builder = builder,
                keys = miuiFocusKeys,
                state = state,
                title = appTitle,
                content = statusLine,
            )
        }

        if (promote && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            promoteToLiveUpdate(builder, state.liveChipWords())
        }

        return builder.build()
    }

    /** 锁屏隐藏敏感内容时的公开版本（对齐 DSH Go publicVersion：不泄露会话标题与正文）。 */
    private fun publicVersion(state: AgentExecutionState, open: PendingIntent): Notification {
        val publicText = getString(R.string.execution_title)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(publicText)
            .setContentIntent(open)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            state.liveChipWords()?.let(builder::setShortCriticalText)
        }
        return builder.build()
    }

    /**
     * 对齐 DSH Go `LiveActivity.post()`：API 36+ 运行态使用不定进度 `Notification.ProgressStyle`，
     * 仅给单段进度轨着雾蓝/壁纸取色，不设通知整体 setColor，保持液态玻璃通透卡片。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun applyLiveProgressStyle(builder: Notification.Builder) {
        val accent = progressAccentColor()
        builder.setStyle(
            Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1).setColor(accent)))
                .setProgressIndeterminate(true),
        )
    }

    /**
     * 申请 Android 16 原生 Live Update 提升：
     * - 正在正计时时 `chipWords` 为 null，顶部胶囊直接显示实时计时器（如 00:18）；
     * - 仅非计时状态才设置 `setShortCriticalText`。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun promoteToLiveUpdate(builder: Notification.Builder, chipWords: String?) {
        chipWords?.takeIf { it.isNotEmpty() }?.let(builder::setShortCriticalText)
        builder.extras.putBoolean(PROMOTED_ONGOING_EXTRA, true)
    }

    /**
     * 对齐 DSH Go `XiaomiFocus.kt`：仅在非提升态（Android <= 15）且 SystemUI `canShowFocus` 放行时写入
     * 最简 protocol 1 模板（仅 `miui.focus.param` + `miui.focus.pics`），零彩虹光边、零自定义撞色、零按钮。
     * 必须在后台通知线程调用，不可阻塞主线程。
     */
    private fun applyXiaomiFocusExtras(
        builder: Notification.Builder,
        keys: List<String>,
        state: AgentExecutionState,
        title: String,
        content: String,
    ) {
        if (suppressLiveUpdateFlag) return
        if (!state.isRunning) return
        if (!isXiaomiFocusAllowed(this)) return
        val word = state.islandShortText()
            .trim()
            .ifBlank { content.trim() }
            .take(AgentExecutionState.ISLAND_RIGHT_MAX_CHARS)
        val paramJson = xiaomiFocusParamJson(
            phase = state.phase,
            word = word,
            title = title,
            content = content,
        )
        val pics = Bundle().apply {
            putParcelable(FOCUS_PIC_ICON, Icon.createWithResource(this@AgentExecutionService, R.mipmap.ic_launcher))
        }
        for (key in keys) {
            when (key) {
                ExecutionNotificationPlan.MIUI_FOCUS_PARAM -> builder.extras.putString(key, paramJson)
                ExecutionNotificationPlan.MIUI_FOCUS_PICS -> builder.extras.putBundle(key, pics)
                else -> Unit
            }
        }
    }

    /**
     * 对齐 DSH Go `XiaomiFocus.param()`：极简 protocol 1 模板，UTF-8 严格限制在 3072 字节以内。
     * 零外发光特效、零按钮、零自定义颜色，100% 跟随系统主题。
     */
    private fun xiaomiFocusParamJson(
        phase: AgentExecutionPhase,
        word: String,
        title: String,
        content: String,
    ): String {
        var safeTitle = title.trim()
        var safeContent = content.trim()
        while (true) {
            val pic = JSONObject().put("type", 1).put("pic", FOCUS_PIC_ICON)
            val paramIsland = JSONObject()
                .put("islandProperty", 1)
                .put(
                    "bigIslandArea",
                    JSONObject().put(
                        "imageTextInfoLeft",
                        JSONObject()
                            .put("type", 1)
                            .put("picInfo", pic)
                            .put("textInfo", JSONObject().put("title", word)),
                    ),
                )
                .put("smallIslandArea", JSONObject().put("picInfo", pic))
            val paramV2 = JSONObject()
                .put("protocol", 1)
                .put("business", "eta_live_${phase.name.lowercase()}")
                .put("updatable", true)
                .put("enableFloat", false)
                .put("ticker", "$word · $safeTitle")
                .put("aodTitle", word)
                .put("param_island", paramIsland)
                .put(
                    "baseInfo",
                    JSONObject()
                        .put("type", 2)
                        .put("title", safeTitle)
                        .put("content", safeContent),
                )
            val json = JSONObject().put("param_v2", paramV2).toString()
            if (json.toByteArray(Charsets.UTF_8).size <= FOCUS_PARAM_MAX_BYTES ||
                (safeTitle.length <= 8 && safeContent.isEmpty())
            ) {
                return json
            }
            if (safeContent.isNotEmpty()) {
                safeContent = safeContent.take(safeContent.length / 2)
            } else {
                safeTitle = safeTitle.take(safeTitle.length / 2)
            }
        }
    }

    companion object {
        const val CHANNEL = "eta_execution_v2"
        private const val LEGACY_CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        /** API 36 系统读取的提升标记；公开的 requestPromotedOngoing() 到 36.1 才存在。 */
        private const val PROMOTED_ONGOING_EXTRA = "android.requestPromotedOngoing"
        /** DSH Go 同款默认雾蓝进度条色值（Android 12+ 优先读取壁纸动态取色 system_accent1_500）。 */
        private const val DEFAULT_PROGRESS_ACCENT = 0xFF4B67B4.toInt()
        private const val MIN_NOTIFY_GAP_MS = 600L
        private const val LIVE_REFRESH_MS = 4 * 60_000L
        private const val LIVE_TIMEOUT_MS = 10 * 60_000L
        private const val FOCUS_PARAM_MAX_BYTES = 3072
        private const val FOCUS_CHECK_TTL_MS = 60_000L
        private const val FOCUS_PROTOCOL_SETTING = "notification_focus_protocol"
        private const val FOCUS_PROVIDER_URI = "content://miui.statusbar.notification.public"
        private const val FOCUS_PIC_ICON = "miui.focus.pic_icon"

        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
        private val notifyHandler: Handler by lazy {
            val thread = HandlerThread("eta-live-notify").apply { start() }
            Handler(thread.looper)
        }
        @Volatile private var instance: AgentExecutionService? = null
        @Volatile private var focusAllowedCache: Pair<Long, Boolean>? = null

        @Volatile var backupMaintenance: Boolean = false
            private set

        /** 外观里换过图标后，异步刷新正在执行的通知。 */
        fun refreshIcon() {
            instance?.scheduleNotificationRefresh(immediate = false)
        }

        @Synchronized fun beginBackupMaintenance() {
            check(!backupMaintenance && leases.count() == 0) { "请先停止 Agent 任务并关闭终端会话，再备份或恢复" }
            backupMaintenance = true
        }

        @Synchronized fun endBackupMaintenance() { backupMaintenance = false }

        const val CHANNEL_COMPLETED = "eta_completed"
        private const val COMPLETION_NOTIFICATION_ID_BASE = 20000
        @Volatile private var executionState = AgentExecutionState()
        /** 最近一次真正下发给通知的执行状态，用于同值去重（避免流式增量把通知推到系统限流）。 */
        @Volatile private var publishedExecutionState: AgentExecutionState? = null
        /**
         * 副屏是否活跃。由 AgentRuntimeService 在快照变化时写入；
         * 通知构建路径只读这个标记，避免抢 VirtualDisplaySession 的监视器锁。
         */
        @Volatile private var virtualDisplayActiveFlag = false
        @Volatile private var suppressLiveUpdateFlag = false
        @Volatile private var currentConversationKey: String? = null

        fun setVirtualDisplayActive(active: Boolean) {
            if (virtualDisplayActiveFlag == active) return
            virtualDisplayActiveFlag = active
            publishedExecutionState = null
            instance?.scheduleNotificationRefresh(immediate = false)
        }

        /**
         * 顶部悬浮胶囊（GUI/虚拟屏）显示期间或静默定时任务执行期间，抑制流体云/灵动岛提升，
         * 避免屏幕顶部同时出现两个胶囊冲突（对齐 Eta 639eeb2 & 90d2191）。
         */
        fun setSuppressLiveUpdate(suppressed: Boolean) {
            if (suppressLiveUpdateFlag == suppressed) return
            suppressLiveUpdateFlag = suppressed
            publishedExecutionState = null
            instance?.scheduleNotificationRefresh(immediate = false)
        }

        fun setConversationKey(conversationKey: String?) {
            currentConversationKey = conversationKey?.takeIf(String::isNotBlank)
        }
        @Volatile private var currentSource: String = AgentNotificationTrampolineActivity.SOURCE_MAIN

        /**
         * 探测焦点通知 / 实时活动可用性（对齐 DSH Go `LiveActivity.status()`）：
         * Android 16+ 原生 Live Update 或小米系 HyperOS 均支持上岛。
         */
        fun islandAvailability(context: Context): IslandAvailability {
            val supported = Build.VERSION.SDK_INT >= AgentExecutionState.LIVE_UPDATE_MIN_SDK || isMiuiFamily()
            if (!supported) return IslandAvailability.UNSUPPORTED
            val enabled = androidx.core.app.NotificationManagerCompat.from(context)
                .areNotificationsEnabled()
            return if (enabled) IslandAvailability.SUPPORTED else IslandAvailability.NO_PERMISSION
        }

        /** 对齐 DSH Go `XiaomiFocus.allowed()`：查协议版本与 SystemUI `canShowFocus`，缓存 60s（仅在后台线程调用）。 */
        private fun isXiaomiFocusAllowed(context: Context): Boolean {
            val now = SystemClock.elapsedRealtime()
            focusAllowedCache?.let { (at, ok) -> if (now - at < FOCUS_CHECK_TTL_MS) return ok }
            val protocol = if (isMiuiFamily()) {
                runCatching {
                    Settings.System.getInt(context.contentResolver, FOCUS_PROTOCOL_SETTING, 0)
                }.getOrDefault(0)
            } else {
                0
            }
            val ok = protocol >= 1 && runCatching {
                val extras = Bundle().apply { putString("package", context.packageName) }
                context.contentResolver
                    .call(Uri.parse(FOCUS_PROVIDER_URI), "canShowFocus", null, extras)
                    ?.getBoolean("canShowFocus", false) == true
            }.getOrDefault(false)
            focusAllowedCache = now to ok
            return ok
        }

        /** 小米系 ROM 判定：HyperOS / MIUI 都会带 miui 或 mi.os 版本属性。 */
        private fun isMiuiFamily(): Boolean {
            fun prop(key: String): String =
                runCatching {
                    val c = Class.forName("android.os.SystemProperties")
                    c.getMethod("get", String::class.java).invoke(null, key) as? String
                }.getOrNull().orEmpty()
            return prop("ro.miui.ui.version.name").isNotEmpty() ||
                prop("ro.mi.os.version.name").isNotEmpty() ||
                prop("ro.mi.os.version.incremental").isNotEmpty()
        }

        fun ensureChannels(context: Context, manager: NotificationManager) {
            // 清理旧的 IMPORTANCE_HIGH 渠道：Android 会持久化已建渠道的重要度，
            // 不删旧换新则覆盖安装仍会在任务启动时强弹悬浮/自动展开灵动岛。
            manager.deleteNotificationChannel(LEGACY_CHANNEL)
            val executionChannel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.execution_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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
            conversationKey: String? = null,
            softInput: Boolean = false,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            ensureChannels(context, manager)
            val intent = Intent(context, AgentNotificationTrampolineActivity::class.java).apply {
                putExtra(AgentNotificationTrampolineActivity.EXTRA_SOURCE, source)
                // 带上会话与入口态：否则点通知只能把应用打开，落回上次停留的界面。
                if (!conversationKey.isNullOrBlank()) putExtra(io.github.mangi.eta.agent.voice.EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, conversationKey)
                if (softInput) putExtra(io.github.mangi.eta.agent.voice.EtaAssistantOverlayService.EXTRA_SOFT_INPUT, true)
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
            }.let(ExecutionNotificationPlan::completionBody)
            // 纯原生系统样式（零自定义 RemoteViews、零按钮），与 HyperOS 液态玻璃锁屏/通知栏完美融合。
            val builder = Notification.Builder(context, CHANNEL_COMPLETED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(displayTitle)
                .setContentText(displayContent)
                .setStyle(Notification.BigTextStyle().bigText(displayContent))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setShowWhen(true)
                // 完成态不申请提升、不占常驻位、不重复提醒：用户要求"需要时自己点开才展开"。
                .setOngoing(false)
                .setOnlyAlertOnce(true)
            // 显式写 false（不是省略）：同一套 extras 模板里避免继承/复制粘贴出提升标记。
            builder.extras.putBoolean(PROMOTED_ONGOING_EXTRA, false)
            val notificationId = COMPLETION_NOTIFICATION_ID_BASE + (runId.hashCode() and 0x7FFF)
            manager.notify(notificationId, builder.build())
        }

        fun updateExecutionState(state: AgentExecutionState) {
            executionState = state
            // 同值去重：避免重复下发撞系统限流或干扰退回桌面动画。
            if (publishedExecutionState == state) return
            publishedExecutionState = state
            instance?.scheduleNotificationRefresh(immediate = false)
        }

        /**
         * 只清状态，不主动重发通知。
         *
         * 运行结束时 Runtime 紧接着调 [release]，由那里决定"还有别的会话 → 重画"或"没有会话 →
         * 撤掉前台通知"。
         */
        fun resetExecutionState() {
            executionState = AgentExecutionState()
            publishedExecutionState = null
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
                publishedExecutionState = null
                suppressLiveUpdateFlag = false
                currentConversationKey = null
                currentSource = AgentNotificationTrampolineActivity.SOURCE_MAIN
            }
            instance?.scheduleNotificationRefresh(immediate = true)
        }
    }
}

/**
 * 焦点通知（超级岛 / 实时活动）在设置页展示的可用性。
 */
enum class IslandAvailability {
    /** 系统支持实时活动 / 焦点通知，且通知权限已授予。 */
    SUPPORTED,
    /** 系统支持，但用户关闭了本应用的通知。 */
    NO_PERMISSION,
    /** 系统不支持。 */
    UNSUPPORTED,
}

/**
 * 执行通知的构建规划：哪些 extras 该写、用哪种样式。
 *
 * 抽成纯函数是为了让「提升态不写 miui.*」「提升态走 ProgressStyle」「非提升态仅写最简 XiaomiFocus 键」
 * 能在 JVM 单测里直接断言，不必把 Notification.Builder / Bundle 拖进测试。
 */
internal object ExecutionNotificationPlan {
    internal const val MIUI_FOCUS_PARAM = "miui.focus.param"
    internal const val MIUI_FOCUS_PICS = "miui.focus.pics"

    /** 对齐 DSH Go `XiaomiFocus.kt`：非提升态兜底仅写 param 与 pics 两项，不强行劫持渲染。 */
    internal val MIUI_FOCUS_KEYS = listOf(
        MIUI_FOCUS_PARAM,
        MIUI_FOCUS_PICS,
    )

    /**
     * 提升态恒为空：HyperOS 的 FocusNotifPreHandler 见到 miui.focus.* 就接管渲染，
     * 原生 Live Update 会被压掉。非提升态仅返回最简两键。
     */
    internal fun miuiFocusKeys(promoted: Boolean): List<String> =
        if (promoted) emptyList() else MIUI_FOCUS_KEYS

    /** 对齐 DSH Go：提升态走不定进度 ProgressStyle；非提升态有展开正文走 BigTextStyle，其余不设样式。 */
    internal fun style(promoted: Boolean, hasExpandedSnippet: Boolean): ExecutionNotificationStyle = when {
        promoted -> ExecutionNotificationStyle.PROGRESS
        hasExpandedSnippet -> ExecutionNotificationStyle.BIG_TEXT
        else -> ExecutionNotificationStyle.NONE
    }

    /**
     * 完成通知正文字数上限：展开卡片够读一段完整回复，同时保持有界
     * （通知 extras 会被系统跨进程复制，不能塞进整篇输出）。
     */
    internal const val MAX_COMPLETION_BODY_CHARS = 800

    /** 完成通知正文：去首尾空白，按码点截断（不切代理对），超长补省略号。 */
    internal fun completionBody(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.codePointCount(0, trimmed.length) <= MAX_COMPLETION_BODY_CHARS) return trimmed
        val end = trimmed.offsetByCodePoints(0, MAX_COMPLETION_BODY_CHARS - 1)
        return trimmed.substring(0, end).trimEnd() + "…"
    }
}

/** 执行通知的样式种类。 */
internal enum class ExecutionNotificationStyle {
    PROGRESS,
    BIG_TEXT,
    NONE,
}
