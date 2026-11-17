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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
        // 副屏存活时在既有常驻通知上补一行状态，不新增通知渠道。
        // 注意：这里绝不能调 VirtualDisplaySession.viewerStatus()——它是 @Synchronized，
        // 而本函数在主线程被 publishExecutionState 调用。工具线程正在做全分辨率截图时
        // 会长时间持锁，主线程就跟着卡在 futex 上：实测连续阻塞 18 秒，
        // 期间胶囊不动、手机整体无法滑动（我们可触摸的浮窗收不了事件）。
        // 改读运行时服务推送过来的非阻塞缓存标记。
        val virtualDisplayActive = virtualDisplayActiveFlag
        val detailWithVirtualDisplay = if (virtualDisplayActive) {
            detailText + " · " + getString(R.string.execution_virtual_display_active)
        } else {
            detailText
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appTitle)
            .setContentText(detailWithVirtualDisplay)
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
        val promote = AgentExecutionState.shouldPromoteToLiveUpdate(state, Build.VERSION.SDK_INT)
        // 提升态不设 subText：Eta 的构建链里只有 title/contentText，没有 subText；而且 subText 与
        // BigTextStyle().setSummaryText(actionText) 是同一句话，展开卡上会重复一行。非提升态保持原样。
        if (!promote) {
            builder.setSubText(actionText)
        }
        // 一律 BigTextStyle：刻意不用无进度的 ProgressStyle，否则提升卡下方会渲染出一条空进度白条。
        // 不设 setColor：用户实测这台机器会把品牌色渲染成深色不透明卡片（纯黑底看不清字），
        // 去掉后由系统按液态玻璃样式着色。
        if (ExecutionNotificationPlan.style(promote, expanded != null) == ExecutionNotificationStyle.BIG_TEXT) {
            builder.setStyle(
                Notification.BigTextStyle()
                    .setBigContentTitle(appTitle)
                    .bigText(expanded ?: detailWithVirtualDisplay)
                    .setSummaryText(actionText),
            )
        }

        // 提升态一个 miui.* 扩展都不写：HyperOS 的 FocusNotifPreHandler 只要看到 miui.focus.* 就自己接管渲染，
        // 原生 Live Update 提升会被压掉。非提升态保持原有 MIUI 焦点通知（超级岛）行为，键集合与写入顺序不变。
        val miuiFocusKeys = ExecutionNotificationPlan.miuiFocusKeys(promote)
        if (miuiFocusKeys.isNotEmpty()) {
            applyMiuiFocusExtras(builder, miuiFocusKeys, appTitle, actionText)
        }

        // 判断逻辑是纯函数（可单测）；这里同时保留显式版本闸门，保证低版本一行 API 36 代码都不碰。
        if (promote && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            promoteToLiveUpdate(builder, state.shortText())
        }

        // 浅色半透明自定义卡片：HyperOS 的原生实时活动模板会把背景画成深色，
        // 与壁纸混在一起后正文几乎看不清。这里自绘一层浅色底，标题/状态/正文都够对比度；
        // 系统若不接受自定义内容视图，会回落到上面的标准模板。
        val executionCard = agentNotificationCard(
            context = this,
            title = appTitle,
            status = actionText,
            body = expanded ?: detailWithVirtualDisplay,
            actionLabel = getString(R.string.execution_stop),
            actionIntent = stop,
        )
        builder.setCustomContentView(executionCard)
        builder.setCustomBigContentView(executionCard)
        return builder.build()
    }

    /**
     * 写入 MIUI 焦点通知（灵动岛）扩展。HyperOS 4 的上岛判定分两个进程、两道闸门：
     *   ① SystemUI 主进程 FocusUtils.isFocusNotification：只认
     *      extras["miui.focus.rv"] 是 RemoteViews，或 extras["miui.focus.isFocus"] 为 true；
     *      随后 showOnStatusBar 还要求 miui.focus.ticker 非空（或 miui.focus.rvBar 是 RemoteViews）。
     *   ② 系统插件 miui.systemui.plugin 的 FocusNotifPreHandler.onNotificationPosted：
     *      有 miui.focus.rv 走 buildNoParamsFocusNotification，
     *      否则读 extras["miui.focus.param"]（protocol 3 JSON）走 buildParamsFocusNotification；
     *      两者都没有则直接 return false，通知永远渲染不出岛。
     * 只写 isFocus / ticker 只能过①，过不了②，所以必须同时提供 miui.focus.param。
     * 下方 JSON 按 param_v2（protocol 3）组织，命中 V3 模板路径（不需要 scene）。
     * 图片经 miui.focus.pics（Bundle: pic 名 -> Icon）提供，JSON 中 pic/src/aodPic 引用同名键。
     * 旧 key（miui.focusNotification / .subTitle）保留，兼容 HyperOS 3 / MIUI 14-15。
     * miui.enableFloat 在 HyperOS 3/4 的 ExpandedNotification.canFloat() 中均被读取，保持 false 不弹浮窗。
     *
     * 只在非提升态调用；提升态交给系统原生 Live Update，这些键一个都不写。
     */
    private fun applyMiuiFocusExtras(
        builder: Notification.Builder,
        keys: List<String>,
        appTitle: String,
        actionText: String,
    ) {
        for (key in keys) {
            when (key) {
                ExecutionNotificationPlan.MIUI_FOCUS_NOTIFICATION ->
                    builder.extras.putBoolean(key, true)
                ExecutionNotificationPlan.MIUI_FOCUS_NOTIFICATION_SUBTITLE ->
                    builder.extras.putString(key, actionText)
                ExecutionNotificationPlan.MIUI_FOCUS_IS_FOCUS ->
                    builder.extras.putBoolean(key, true)
                ExecutionNotificationPlan.MIUI_FOCUS_TICKER ->
                    builder.extras.putString(key, actionText)
                ExecutionNotificationPlan.MIUI_ENABLE_FLOAT ->
                    builder.extras.putBoolean(key, false)
                ExecutionNotificationPlan.MIUI_FOCUS_PARAM ->
                    builder.extras.putString(key, focusParamJson(appTitle, actionText))
                ExecutionNotificationPlan.MIUI_FOCUS_PICS ->
                    builder.extras.putBundle(key, focusPics())
                ExecutionNotificationPlan.MIUI_FOCUS_ACTIONS ->
                    builder.extras.putBundle(key, Bundle.EMPTY)
                else -> Unit
            }
        }
    }

    /**
     * 把通知标记为 Live Update：胶囊短文本 + 提升 extra。
     *
     * 请求提升的公开方法（requestPromotedOngoing）到 API 36.1 才公开，所以这里按 Eta 的做法
     * 用标准 extra `android.requestPromotedOngoing`，API 36 的系统同样读取它。
     *
     * 不设大图标：用户实测这张卡上大图标会渲染成突兀的白色方块，按用户要求去掉。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun promoteToLiveUpdate(builder: Notification.Builder, shortText: String) {
        if (shortText.isNotEmpty()) {
            builder.setShortCriticalText(shortText)
        }
        builder.extras.putBoolean(PROMOTED_ONGOING_EXTRA, true)
    }

    /** HyperOS 4 焦点通知 protocol 3 参数；结构对齐系统实测样本，只保留文字与一个图标。 */
    private fun focusParamJson(title: String, content: String): String {
        val picInfo = JSONObject()
            .put("type", 1)
            .put("pic", FOCUS_PIC_ICON)
            .put("loop", false)
            .put("autoplay", false)
            .put("number", 0)
        val leftText = JSONObject()
            .put("title", title)
            .put("showHighlightColor", false)
            .put("narrowFont", false)
        val rightText = JSONObject()
            .put("title", content)
            .put("showHighlightColor", false)
            .put("narrowFont", false)
        val bigIslandArea = JSONObject()
            .put(
                "imageTextInfoLeft",
                JSONObject().put("type", 1).put("picInfo", picInfo).put("textInfo", leftText),
            )
            .put("imageTextInfoRight", JSONObject().put("type", 2).put("textInfo", rightText))
        val paramIsland = JSONObject()
            .put("islandProperty", 1)
            .put("islandPriority", 2)
            .put("islandTimeout", 999)
            .put("islandOrder", false)
            .put("dismissIsland", false)
            .put("maxSize", false)
            .put("needCloseAnimation", true)
            .put("bigIslandArea", bigIslandArea)
            .put("smallIslandArea", JSONObject().put("picInfo", picInfo))
            .put("outEffectSrc", "outer_glow")
        val paramV2 = JSONObject()
            .put("protocol", 3)
            .put("business", "hyper_island_dispatch")
            .put("updatable", true)
            .put("ticker", content)
            .put("enableFloat", false)
            .put("isShowNotification", false)
            .put("islandFirstFloat", false)
            .put("param_island", paramIsland)
            .put(
                "iconTextInfo",
                JSONObject()
                    .put(
                        "animIconInfo",
                        JSONObject().put("type", 0).put("src", FOCUS_PIC_ICON).put("loop", true).put("autoplay", true),
                    )
                    .put("title", title)
                    .put("content", content),
            )
            .put("outEffectSrc", "outer_glow")
            .put("aodTitle", content)
            .put("aodPic", FOCUS_PIC_ICON)
        return JSONObject().put("param_v2", paramV2).put("isShowNotification", true).toString()
    }

    /** miui.focus.pics：键为 JSON 中引用的 pic / src / aodPic 名，值为 Icon。 */
    private fun focusPics(): Bundle = Bundle().apply {
        putParcelable(FOCUS_PIC_ICON, Icon.createWithResource(this@AgentExecutionService, R.drawable.ic_notification))
    }


    companion object {
        const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        /** API 36 系统读取的提升标记；公开的 requestPromotedOngoing() 到 36.1 才存在。 */
        private const val PROMOTED_ONGOING_EXTRA = "android.requestPromotedOngoing"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null

        @Volatile var backupMaintenance: Boolean = false
            private set

        /** 外观里换过图标后，刷新正在执行的通知（大图标跟随当前启动图标）。 */
        fun refreshIcon() {
            mainHandler.post { instance?.refreshNotification() }
        }

        @Synchronized fun beginBackupMaintenance() {
            check(!backupMaintenance && leases.count() == 0) { "请先停止 Agent 任务并关闭终端会话，再备份或恢复" }
            backupMaintenance = true
        }

        @Synchronized fun endBackupMaintenance() { backupMaintenance = false }

        const val CHANNEL_COMPLETED = "eta_completed"
        private const val COMPLETION_NOTIFICATION_ID_BASE = 20000
        private const val FOCUS_PIC_ICON = "eta_island_icon"
        @Volatile private var executionState = AgentExecutionState()
        /** 最近一次真正下发给通知的执行状态，用于同值去重（避免流式增量把通知推到系统限流）。 */
        @Volatile private var publishedExecutionState: AgentExecutionState? = null
        /**
         * 副屏是否活跃。由 AgentRuntimeService 在快照变化时写入；
         * 通知构建路径只读这个标记，避免在主线程上抢 VirtualDisplaySession 的监视器锁。
         */
        @Volatile private var virtualDisplayActiveFlag = false

        fun setVirtualDisplayActive(active: Boolean) {
            virtualDisplayActiveFlag = active
        }
        @Volatile private var currentSource: String = AgentNotificationTrampolineActivity.SOURCE_MAIN

        /**
         * 探测焦点通知（超级岛）可用性。
         *
         * 本项目没有 Number444 的 `AgentIslandNotifier`，上岛能力全部由本服务在
         * [notification] 里通过 `miui.focus.*` extras 提供（HyperOS 3/4、MIUI 14+）。
         * 因此这里按「系统是否小米系 + 通知是否开启」判定，不去反射系统插件类：
         * 插件内部类名随版本变动，反射失败会把「支持」误报成「不支持」。
         */
        fun islandAvailability(context: Context): IslandAvailability {
            if (!isMiuiFamily()) return IslandAvailability.UNSUPPORTED
            val enabled = androidx.core.app.NotificationManagerCompat.from(context)
                .areNotificationsEnabled()
            return if (enabled) IslandAvailability.SUPPORTED else IslandAvailability.NO_PERMISSION
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
            val executionChannel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.execution_channel),
                NotificationManager.IMPORTANCE_HIGH,
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

        fun agentNotificationCard(
            context: Context,
            title: String,
            status: String,
            body: String,
            actionLabel: String?,
            actionIntent: android.app.PendingIntent?,
        ): android.widget.RemoteViews =
            android.widget.RemoteViews(context.packageName, R.layout.notification_agent_card).apply {
                setTextViewText(R.id.agent_notification_title, title)
                setTextViewText(R.id.agent_notification_status, status)
                setTextViewText(R.id.agent_notification_body, body)
                if (!actionLabel.isNullOrBlank() && actionIntent != null) {
                    setTextViewText(R.id.agent_notification_action, actionLabel)
                    setViewVisibility(R.id.agent_notification_action, android.view.View.VISIBLE)
                    setOnClickPendingIntent(R.id.agent_notification_action, actionIntent)
                } else {
                    setViewVisibility(R.id.agent_notification_action, android.view.View.GONE)
                }
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
            builder.setCustomContentView(agentNotificationCard(
                context = context,
                title = displayTitle,
                status = "已完成",
                body = displayContent,
                actionLabel = "查看",
                actionIntent = open,
            ))
            builder.setCustomBigContentView(agentNotificationCard(
                context = context,
                title = displayTitle,
                status = "已完成",
                body = displayContent,
                actionLabel = "查看",
                actionIntent = open,
            ))
            manager.notify(notificationId, builder.build())
        }

        fun updateExecutionState(state: AgentExecutionState) {
            executionState = state
            // 流式增量常常产出完全相同的状态（同一段文案、同一 phase），重复下发只会撞系统限流；
            // 真正的节流在 AgentExecutionNotificationTracker 里（≥1s 一次），这里只做同值去重。
            if (publishedExecutionState == state) return
            publishedExecutionState = state
            mainHandler.post { instance?.refreshNotification() }
        }

        /**
         * 只清状态，不主动重发通知。
         *
         * 运行结束时 Runtime 紧接着调 [release]，由那里决定"还有别的会话 → 重画"或"没有会话 →
         * 撤掉前台通知"。此前这里也刷一次，会把 1107 号通知从运行态改成空闲态再刷一遍（非提升态会带
         * miui.focus.* 参数），HyperOS 见到焦点通知就把灵动岛展开——正是用户反馈的"任务完成自动展开"。
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
                currentSource = AgentNotificationTrampolineActivity.SOURCE_MAIN
            }
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}

/**
 * 焦点通知（超级岛）在设置页展示的可用性。
 *
 * 放在顶层：它需要被 `io.github.mangi.eta.ui` 包引用，而嵌套在 companion 里的类型
 * 跨文件限定名容易歧义（`AgentExecutionService.IslandAvailability` 与
 * `AgentExecutionService.Companion.IslandAvailability` 两种写法行为不一致）。
 */
enum class IslandAvailability {
    /** 系统支持焦点通知，且通知权限已授予。 */
    SUPPORTED,
    /** 系统支持，但用户关闭了本应用的通知。 */
    NO_PERMISSION,
    /** 非小米系/HyperOS 系统，或版本过低，焦点通知不会上岛。 */
    UNSUPPORTED,
}

/**
 * 执行通知的构建规划：哪些 extras 该写、用哪种样式。
 *
 * 抽成纯函数是为了让「提升态不写 miui.*」「低版本样式不变」这两条能在 JVM 单测里直接断言，
 * 不必把 Notification.Builder / Bundle 拖进测试。
 */
internal object ExecutionNotificationPlan {
    internal const val MIUI_FOCUS_NOTIFICATION = "miui.focusNotification"
    internal const val MIUI_FOCUS_NOTIFICATION_SUBTITLE = "miui.focusNotification.subTitle"
    internal const val MIUI_FOCUS_IS_FOCUS = "miui.focus.isFocus"
    internal const val MIUI_FOCUS_TICKER = "miui.focus.ticker"
    internal const val MIUI_ENABLE_FLOAT = "miui.enableFloat"
    internal const val MIUI_FOCUS_PARAM = "miui.focus.param"
    internal const val MIUI_FOCUS_PICS = "miui.focus.pics"
    internal const val MIUI_FOCUS_ACTIONS = "miui.focus.actions"

    /** 非提升态要写的 MIUI 焦点通知键，顺序即写入顺序。 */
    internal val MIUI_FOCUS_KEYS = listOf(
        MIUI_FOCUS_NOTIFICATION,
        MIUI_FOCUS_NOTIFICATION_SUBTITLE,
        MIUI_FOCUS_IS_FOCUS,
        MIUI_FOCUS_TICKER,
        MIUI_ENABLE_FLOAT,
        MIUI_FOCUS_PARAM,
        MIUI_FOCUS_PICS,
        MIUI_FOCUS_ACTIONS,
    )

    /**
     * 提升态恒为空：HyperOS 的 FocusNotifPreHandler 见到 miui.focus.* 就接管渲染，
     * 原生 Live Update 会被压掉。非提升态保持原有全部键。
     */
    internal fun miuiFocusKeys(promoted: Boolean): List<String> =
        if (promoted) emptyList() else MIUI_FOCUS_KEYS

    /** 提升态与「有展开正文」态用 BigTextStyle；其余不设样式（改动前行为）。 */
    internal fun style(promoted: Boolean, hasExpandedSnippet: Boolean): ExecutionNotificationStyle =
        if (promoted || hasExpandedSnippet) ExecutionNotificationStyle.BIG_TEXT else ExecutionNotificationStyle.NONE

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

/** 执行通知的样式种类；刻意不提供无进度的 ProgressStyle（会渲染空进度白条）。 */
internal enum class ExecutionNotificationStyle {
    BIG_TEXT,
    NONE,
}
