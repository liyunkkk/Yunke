package io.github.mangi.eta.agent.runtime

import android.app.Service
import io.github.mangi.eta.agent.question.AgentQuestionReceipt
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentOverlayRunningSession
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyControlledEvent
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceMessenger = Messenger(IncomingHandler())

    private val sessions = AgentRuntimeSessionRegistry()
    private val questionLedger by lazy { io.github.mangi.eta.agent.question.AgentQuestionLedgerAndroid.forContext(applicationContext) }
    private val stopWorker = AgentRuntimeStopWorker { failure ->
        AndroidAgentLogger.warn("Runtime stop failed: type=${failure.safeLogType()}")
    }
    @Volatile private var destroyed = false
    private val pendingStartRequests = linkedMapOf<String, PendingStartRequest>()
    // Keep the originating identity and frozen mode even after terminal registry removal.
    @Volatile
    private var overlaySession: AgentRuntimeSession? = null
    private val overlayRunId: String?
        get() = overlaySession?.runId
    @Volatile private var executionNotificationTracker: AgentExecutionNotificationTracker? = null

    private data class PendingStartRequest(
        val incoming: AgentRuntimeWire.IncomingRunRequest,
        val replyTo: Messenger?,
    )

    private var windowManager: WindowManager? = null
    private var capsuleView: ComposeView? = null
    // 单元测试里把它置 false，可只验证窗口与字段装配而不触发重型 Compose 合成
    // （Robolectric 下真实合成 + 无限动画帧会让 idle() 卡数分钟直至超时）。
    @JvmField internal var renderOverlayContent: Boolean = true
    private var resultCardView: ComposeView? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null

    // 收起态最后停留的位置（拖动/贴边后更新），展开再收回时回到这里。
    private var capsuleCollapsedX = Int.MIN_VALUE
    private var capsuleCollapsedY = Int.MIN_VALUE
    // 上次保存的位置（重启后从 Prefs 读回来）。
    private var savedCapsuleX = -1
    private var savedCapsuleY = -1
    // 小胶囊当前所在位置（像素，窗口坐标），给胶囊界面用来对齐展开面板。
    private val capsuleOffsetX = mutableStateOf(0)
    private val capsuleOffsetY = mutableStateOf(0)
    // 窗口是不是全屏展开态：全屏时才需要把面板摆回小胶囊的位置。
    private val capsuleFullScreen = mutableStateOf(false)
    private val capsuleExpanded = mutableStateOf(false)

    // 多会话展示信息：胶囊展开后点一条就能跳回对应会话。
    private data class RunningSessionInfo(val runId: String, val conversationId: String, val title: String)
    private val runningSessionInfo = linkedMapOf<String, RunningSessionInfo>()
    private val recentSessionInfo = linkedMapOf<String, RunningSessionInfo>()
    // 最近打开过的会话 id（最新在前，内部最多记 RECENT_OPENED_KEEP 条，面板只显示 RECENT_OPENED_SHOWN 条）。
    private val recentOpenedIds = ArrayDeque<String>()
    private val recentOpenedState = mutableStateOf<List<AgentOverlayRunningSession>>(emptyList())
    // 完成但还没打开过的会话（最新在前）：面板「最新回复」栏用。
    private val unreadSessionIds = LinkedHashSet<String>()
    private val unreadSessionState = mutableStateOf<List<AgentOverlayRunningSession>>(emptyList())
    // 未读完成条数：小胶囊角标数字。
    private var unreadFinishedCount = 0

    private val state = mutableStateOf(AgentOverlayState.Initial)
    private var hasExecutedForegroundTool = false
    private val supplementsLock = Any()
    private val supplementsByRunId = linkedMapOf<String, RunSupplements>()
    @Volatile
    private var lastCompletedRunContext: CompletedRunContext? = null
    private val hideToken = Any()
    private val pendingHistorySnapshots = ConcurrentHashMap<String, AgentRuntimeHistoryTransfer.PreparedHistory>()
    private val pendingResultTranscripts =
        ConcurrentHashMap<String, AgentRuntimeTranscriptTransfer.PreparedTranscript>()

    private val pendingCompactionTransfers = ConcurrentHashMap<String, MutableList<AgentRuntimeTranscriptTransfer.PreparedTranscript>>()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        lifecycleScope.launch {
            AgentChildTaskGroups.revision.collect { stopIfRuntimeIdle() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!capsuleExpanded.value) restoreCapsuleCollapsedPosition()
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || (sessions.isEmpty() && !AgentChildTaskGroups.hasAnyActive())) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (sessions.anyNonTerminal()) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        // Losing the presentation/service is not consent to stop children. Keep their snapshots
        // and pending choices; a replacement service must not receive a deferred global stop.
        destroyed = true
        val retiring = sessions.snapshot()
        retiring.forEach { it.signalStop() }
        // Service destruction also runs on Main. Drain accepted stop work independently of
        // the lifecycle; neither child coordination nor resource cancellation may block Main.
        stopWorker.close(retiring.map { session ->
            {
                session.controller.cancel()
                try {
                    AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.USER_CANCEL)
                } finally {
                    session.cancel("Agent Runtime 服务已停止")
                }
                Unit
            }
        })
        failPendingStarts("Agent Runtime 服务已停止")
        overlaySession = null
        mainHandler.removeCallbacksAndMessages(null)
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        resultCardParams = null
        capsuleParams = null
        windowManager = null
        pendingResultTranscripts.values.forEach { prepared -> runCatching { prepared.close() } }
        pendingResultTranscripts.clear()
        pendingHistorySnapshots.values.forEach { it.close() }
        pendingHistorySnapshots.clear()
        pendingCompactionTransfers.values.forEach { transfers -> synchronized(transfers) { transfers.forEach { it.close() } } }
        pendingCompactionTransfers.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.prompt.isBlank() && incoming.images.isEmpty())) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo)
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_STOP_MAIN_RUN -> {
                    val data = msg.data ?: return
                    val runId = AgentRuntimeWire.runIdFromBundle(data)
                    val reason = if (data.getString("child_stop_reason") == AgentChildControlPolicy.Reason.SETTINGS_CHANGED.name) {
                        AgentChildControlPolicy.Reason.SETTINGS_CHANGED
                    } else AgentChildControlPolicy.Reason.USER_STOP
                    if (runId.isNotBlank()) stopMainRun(runId, reason)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId)
                    releaseResultTranscript(runId)
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }

                AgentRuntimeWire.MSG_QUERY_HISTORY -> {
                    val data = msg.data ?: return
                    val reply = msg.replyTo ?: return
                    val runId = AgentRuntimeWire.runIdFromBundle(data)
                    val snapshotId = data.getString("history_snapshot_id").orEmpty()
                    val conversationId = data.getString("conversation_id").orEmpty()
                    val queryId = data.getString("history_query_id").orEmpty()
                    val capturedSession = sessions.get(runId)
                    thread(name = "eta-history-query") {
                        val response = AgentRuntimeWire.ackBundle(runId).apply {
                            putString("history_snapshot_id", snapshotId)
                            putString("history_query_id", queryId)
                            putString("conversation_id", conversationId)
                            putBoolean("history_available", false)
                        }
                        var prepared: AgentRuntimeHistoryTransfer.PreparedHistory? = null
                        try {
                            require(runId.isNotBlank() && runId.length <= 1024 &&
                                snapshotId.length <= 128 && conversationId.isNotBlank() && conversationId.length <= 1024 &&
                                queryId.matches(Regex("[0-9a-f-]{36}")))
                            // A UUID does not confer ownership: check the captured run's UI handoff.
                            val owner = synchronized(supplementsLock) { supplementsByRunId[runId]?.conversationId }
                            val snapshot = capturedSession?.takeIf { sessions.contains(it) && owner == conversationId }
                                ?.historySnapshot(snapshotId)
                            if (snapshot != null) {
                                prepared = AgentRuntimeHistoryTransfer.prepareSnapshot(this@AgentRuntimeService, snapshot.history)
                                response.putInt("snapshot_round", snapshot.round)
                                response.putParcelable(AgentRuntimeWire.KEY_HISTORY_FD, requireNotNull(prepared).descriptor)
                                response.putBoolean("history_available", true)
                            }
                            if (prepared != null) {
                                val transfer = requireNotNull(prepared)
                                check(pendingHistorySnapshots.putIfAbsent(queryId, transfer) == null)
                                // Keep the same-process descriptor alive until the receiver consumes it.
                                // A lost/late client is cleaned without affecting the running session.
                                mainHandler.postDelayed({
                                    if (pendingHistorySnapshots.remove(queryId, transfer)) transfer.close()
                                }, 60_000L)
                            }
                            reply.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_HISTORY_RESPONSE).apply { this.data = response })
                            prepared = null // Pending transfer now owns it; release/timeout closes it.
                        } catch (failure: Exception) {
                            response.remove(AgentRuntimeWire.KEY_HISTORY_FD)
                            response.putBoolean("history_available", false)
                            runCatching { reply.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_HISTORY_RESPONSE).apply { this.data = response }) }
                        } finally {
                            prepared?.let { transfer ->
                                pendingHistorySnapshots.remove(queryId, transfer)
                                transfer.close()
                            }
                        }
                    }
                }

                AgentRuntimeWire.MSG_RELEASE_HISTORY -> {
                    msg.data?.getString("history_query_id")?.let { pendingHistorySnapshots.remove(it)?.close() }
                }

                AgentRuntimeWire.MSG_QUERY_QUESTION -> {
                    val data = msg.data ?: return
                    val reply = msg.replyTo ?: return
                    thread(name = "eta-question-query") {
                        val snapshot = runCatching {
                            val conversationId = data.getString("conversation_id").orEmpty()
                            val runId = AgentRuntimeWire.runIdFromBundle(data)
                            val questionId = data.getString("question_id").orEmpty()
                            val toolCallId = data.getString("tool_call_id").orEmpty()
                            require(listOf(conversationId, runId, questionId, toolCallId).all { it.isNotBlank() && it.length <= 1024 })
                            AgentQuestionAuthority.query(sessions, questionLedger,
                                conversationId, runId, questionId, toolCallId)
                        }.getOrNull()
                        runCatching { reply.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_QUESTION_RESPONSE).apply {
                            this.data = AgentRuntimeWire.questionSnapshotBundle(data, snapshot)
                        }) }
                    }
                }

                AgentRuntimeWire.MSG_QUESTION_ANSWER -> {
                    val data = msg.data ?: return
                    val reply = msg.replyTo ?: return
                    // Decoding is bounded; answer validation and event callbacks do not run on Main.
                    val questionId = data.getString("question_id").orEmpty().take(1024)
                    thread(name = "eta-question-answer") {
                        val receipt = runCatching {
                            val submission = AgentRuntimeWire.questionAnswerFromBundle(data)
                            sessions.get(submission.runId)?.submitQuestionAnswer(submission)
                                ?: AgentQuestionReceipt(false, "QUESTION_RUN_NOT_ACTIVE", "该任务已停止或结束")
                        }.getOrElse {
                            AgentQuestionReceipt(false, "QUESTION_INVALID_ANSWER", "回答格式无效")
                        }
                        runCatching {
                            reply.send(Message.obtain(null, AgentRuntimeWire.MSG_QUESTION_ANSWER_RESPONSE).apply {
                                this.data = AgentRuntimeWire.questionReceiptBundle(questionId, receipt)
                            })
                        }
                    }
                }

                AgentRuntimeWire.MSG_STEER_RUN -> {
                    val data = msg.data ?: return
                    requestSupplementForRun(
                        runId = AgentRuntimeWire.runIdFromBundle(data),
                        text = AgentRuntimeWire.steerTextFromBundle(data),
                        requestId = data.getString("request_id").orEmpty(),
                        imagesJson = data.getString("images_json") ?: "[]",
                    )
                }

                AgentRuntimeWire.MSG_PAUSE_RUN -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    requestPause(runId)
                }

                AgentRuntimeWire.MSG_RESUME_RUN -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    requestResume(runId)
                }

                AgentRuntimeWire.MSG_COMPACT_RUN -> {
                    val data = msg.data ?: return
                    val accepted = requestCompactRun(
                        runId = AgentRuntimeWire.runIdFromBundle(data),
                        keepRecent = AgentRuntimeWire.compactKeepRecentFromBundle(data),
                        compressModelConfig = AgentRuntimeWire.compactModelConfigFromBundle(data),
                        childTaskId = AgentRuntimeWire.compactChildTaskFromBundle(data),
                    )
                    msg.replyTo?.let { reply -> runCatching {
                        reply.send(Message.obtain(null, AgentRuntimeWire.MSG_COMPACT_RUN).apply {
                            this.data = android.os.Bundle().apply { putBoolean("compact_accepted", accepted) }
                        })
                    } }
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        val runId = incoming.request.runId
        pendingStartRequests.remove(runId)?.let { previous ->
            failPendingStart(previous, "已被同一任务的新请求替换")
        }
        val pending = PendingStartRequest(incoming, replyTo)
        pendingStartRequests[runId] = pending
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                val request = AgentRuntimeImageTransfer.materialize(incoming)
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        RuntimeConfigRepository.currentRuntimeConfig()
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                if (pendingStartRequests[runId] !== pending) return@post
                pendingStartRequests.remove(runId)
                sendRequestIngestedTo(replyTo, incoming.request.runId)
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        startRun(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                            replyTo,
                        )
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 YUNKe 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        sessions.get(request.runId)?.let { previous ->
            previous.signalStop()
            stopWorker.submit(previous) {
                previous.controller.cancel()
                try { AgentChildRunControl.terminate(previous, AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
                finally { previous.cancel("已被同一任务的新请求替换") }
            }
        }
        val session = AgentRuntimeSession(
            runId = request.runId,
            questionLedger = questionLedger,
            terminalWork = { action ->
                if (Looper.myLooper() == Looper.getMainLooper()) thread(name = "eta-question-seal", block = action)
                else action()
                Unit
            },
            eventSink = { event -> sendEventTo(replyTo, event, request.runId) },
            resultSink = { result -> sendResultTo(replyTo, result) },
        )
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionSource = if (request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
            AgentNotificationTrampolineActivity.SOURCE_OVERLAY
        } else {
            AgentNotificationTrampolineActivity.SOURCE_MAIN
        }
        val runLease = AgentRuntimeRunLease.create(request.runId)
        val executionHeld = AgentExecutionService.acquire(
            this, runLease.id, allowBoundFallback = allowBoundFallback,
        ) {
            session.signalStop()
            stopWorker.submit(session) {
                session.controller.cancel()
                session.cancel("已停止")
            }
        }
        if (!executionHeld && (!allowBoundFallback || AgentExecutionService.backupMaintenance)) {
            session.complete(AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = "无法启动后台执行服务，请返回 YUNKe 后重试",
            )) {}
            return
        }
        AgentChildRunControl.begin(session)
        sessions.put(session)
        if (overlayRunId == request.runId) {
            // ASK 选前台后才会用悬浮窗，先按前台保留入口；选后台时后续事件不会再显示。
            if (AgentOverlayVisibilityPolicy.mayUseOverlay(session.taskSurfaceMode)) {
                overlaySession = session
            } else {
                // Only retire the replaced run's windows, never another foreground run's.
                dismissAndStop()
            }
        }
        if (lastCompletedRunContext?.request?.runId == request.runId) {
            lastCompletedRunContext = null
        }
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        if (AgentOverlayVisibilityPolicy.mayUseOverlay(session.taskSurfaceMode)) {
            mainHandler.removeCallbacksAndMessages(hideToken)
            state.value = AgentOverlayState.Initial
            if (overlayRunId == null || overlaySession === session) {
                hasExecutedForegroundTool = false
            }
        }
        // 记一笔这个会话的展示信息，供胶囊会话列表与跳转使用。
        run {
            val payload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.payload?.let { AgentUiHandoffPayload.from(it) }
            val conversationId = payload?.conversationId.orEmpty()
            runningSessionInfo[request.runId] = RunningSessionInfo(
                runId = request.runId,
                conversationId = conversationId,
                title = conversationId.takeIf { it.isNotBlank() } ?: "会话",
            )
            syncRunningSessions()
            // 副屏运行也要常驻胶囊：有任意会话就确保浮层出现。
            if (AgentOverlayVisibilityPolicy.allowsCapsule(session.taskSurfaceMode)) {
                claimOverlay(session, wantsOverlay = overlaySession == null)
                ensureOverlayVisible()
            }
        }
        synchronized(supplementsLock) {
            val extras = RunSupplements(conversationId = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.payload?.let { AgentUiHandoffPayload.from(it).conversationId }.orEmpty())
            if (request.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                val payload = AgentUiHandoffPayload.from(request.handoff.payload)
                extras.items += payload.supplements
                extras.nextIndex = (
                    listOfNotNull(payload.promptSupplement?.index) +
                        payload.supplements.map { it.index }
                    ).maxOrNull()?.plus(1) ?: 1
            }
            supplementsByRunId[request.runId] = extras
        }

        val notificationTracker = AgentExecutionNotificationTracker(
            context = this,
            handler = mainHandler,
            onStateChanged = { executionState ->
                AgentExecutionService.updateExecutionState(executionState)
            },
        )
        executionNotificationTracker = notificationTracker
        try {
            thread(name = "agent-runtime") {
                try {
                    executeRun(session, request)
                } finally {
                    notificationTracker.reset()
                    if (executionNotificationTracker === notificationTracker) {
                        executionNotificationTracker = null
                    }
                    AgentExecutionService.resetExecutionState()
                    AgentChildRunControl.finish(session)
                    AgentExecutionService.release(runLease.id)
                }
            }
        } catch (failure: Throwable) {
            AgentChildRunControl.finish(session)
            AgentExecutionService.release(runLease.id)
            val result = AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "", error = "Agent Runtime 无法启动执行线程",
            )
            AndroidAgentLogger.error("Agent runtime thread start failed: type=${failure.safeLogType()}")
            runCatching { persistCompletedRun(request, result) }
            session.complete(result) {}
            postTerminalOverlay(session, result, null)
        }
    }

    private fun executeRun(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = { it.withActiveSupplements() },
            onAcceptedEvent = { event, entrySurfaceGuard ->
                handleAcceptedRunEvent(session, event, entrySurfaceGuard)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(session, request)
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            session = session,
            result = outcome.result,
            entrySurfaceGuard = outcome.entrySurfaceGuard,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    CompletedRunContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        if (!sessions.contains(session)) return
        // 胶囊（含副屏常驻）在任何模式都要跟进状态；只有「前台操作高亮」才按前台模式门控。
        if (!AgentOverlayVisibilityPolicy.allowsCapsule(session.taskSurfaceMode)) return
        val revealsForegroundOperation =
            AgentOverlayVisibilityPolicy.shouldRevealFor(event, session.taskSurfaceMode)
        val requiresEntrySurfaceDismissal =
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event, session.taskSurfaceMode)
        val entrySurfaceReady = if (requiresEntrySurfaceDismissal && entrySurfaceGuard != null) {
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)
        } else {
            true
        }
        mainHandler.post {
            if (!sessions.contains(session)) return@post
            executionNotificationTracker?.onEvent(event)
            if (
                AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                    event,
                    entrySurfaceReady,
                    session.taskSurfaceMode,
                )
            ) {
                hasExecutedForegroundTool = true
            }
            if (session.isTerminal) return@post
            runCatching {
                // 后台副屏会话也允许占用胶囊：没有前台会话抢占时就显示它的进度。
                val ownsOverlay = claimOverlay(
                    session,
                    wantsOverlay = (revealsForegroundOperation && entrySurfaceReady) ||
                        !AgentOverlayVisibilityPolicy.allowsForegroundReveal(session.taskSurfaceMode),
                )
                if (ownsOverlay) {
                    state.value = state.value.applyControlledEvent(event, session.controller.isPaused)
                }
                // 胶囊本体：有会话就常驻（前台/副屏都显示）。
                val firstReveal = capsuleView == null
                ensureOverlayVisible()
                if (firstReveal && revealsForegroundOperation && entrySurfaceReady && ownsOverlay) {
                    AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.RUN_STARTED)
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        entrySurfaceGuard: EntrySurfaceGuard?,
        completedContext: CompletedRunContext? = null,
    ) {
        mainHandler.post {
            // Removal is identity-based: a late callback cannot consume a replacement
            // session's supplements or reveal its windows just because run IDs match.
            if (!sessions.remove(session)) return@post
            synchronized(supplementsLock) { supplementsByRunId.remove(session.runId) }
            if (
                !AgentOverlayVisibilityPolicy.allowsCapsule(session.taskSurfaceMode) ||
                overlaySession !== session
            ) {
                if (sessions.isEmpty() && pendingStartRequests.isEmpty() && !AgentChildTaskGroups.hasAnyActive() &&
                    capsuleView == null && resultCardView == null
                ) {
                    stopSelf()
                }
                return@post
            }
            lastCompletedRunContext = completedContext
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                        session = session,
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                        session = session,
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(target: Messenger?, event: AgentEvent, runId: String) {
        if (target == null) return
        var prepared: AgentRuntimeTranscriptTransfer.PreparedTranscript? = null
        try {
            if (event is AgentEvent.ContextCompacted && event.applied && event.history.isNotEmpty()) {
                prepared = AgentRuntimeTranscriptTransfer.prepare(this, event.history)
            }
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event, prepared?.descriptor).apply {
                if (event is AgentEvent.AssistantBlockDelta) {
                    putLong(StreamDeliveryTiming.KEY, android.os.SystemClock.elapsedRealtimeNanos())
                }
            }
            target.send(msg)
            prepared?.let { transfer ->
                val pending = pendingCompactionTransfers.computeIfAbsent(runId) { mutableListOf() }
                synchronized(pending) { pending += transfer }
            }
        } catch (failure: Exception) {
            prepared?.close()
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${failure.safeLogType()}"
            }
        }
    }

    private fun sendResultTo(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        if (target == null) return
        val prepared = runCatching {
            AgentRuntimeTranscriptTransfer.prepare(this, result.transcript)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_transcript_prepare_failed") {
                "Agent runtime result transcript prepare failed: type=${throwable.safeLogType()}"
            }
        }.getOrNull()
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, prepared?.descriptor)
            target.send(msg)
            if (prepared != null) {
                val key = result.runId.ifBlank { "anonymous-${System.nanoTime()}" }
                pendingResultTranscripts.put(key, prepared)?.close()
            }
        }.onFailure { throwable ->
            prepared?.close()
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun releaseResultTranscript(runId: String) {
        if (runId.isBlank()) return
        pendingResultTranscripts.remove(runId)?.close()
        pendingCompactionTransfers.remove(runId)?.let { transfers -> synchronized(transfers) { transfers.forEach { it.close() } } }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                AgentRuntimeResultStore.list(this)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.activeRunsBundle(sessions.activeRunIds())
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?, attached: Boolean = false) {
        val session = sessions.get(runId)
        val attachedToSession = replyTo != null &&
            runId.isNotBlank() &&
            session != null &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event, runId) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attachedToSession) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
        // Preparation failures have no session/overlay ownership. They must not
        // reuse another run's foreground-execution flag or terminal windows.
        if (sessions.isEmpty() && pendingStartRequests.isEmpty() && !AgentChildTaskGroups.hasAnyActive() &&
            capsuleView == null && resultCardView == null
        ) {
            stopSelf()
        }
    }

    private fun requestStop() {
        val runId = overlayRunId ?: sessions.activeRunIds().lastOrNull()
        if (runId == null) {
            dismissAndStop()
            return
        }
        stopMainRun(runId)
    }

    private fun stopMainRun(
        runId: String,
        reason: AgentChildControlPolicy.Reason = AgentChildControlPolicy.Reason.USER_STOP,
    ) {
        if (runId.isBlank()) return
        pendingStartRequests.remove(runId)?.let { pending -> failPendingStart(pending, "已停止") }
        val session = sessions.get(runId) ?: return
        // Capture this exact session before leaving Main; a delayed stop must never re-resolve
        // runId and cancel a replacement. Blocking cancellation used to starve UI watchdogs.
        session.signalStop()
        stopWorker.submit(session) {
            if (!session.isTerminal) {
                // Publish the child-choice decision before requestStop cancels the controller;
                // otherwise the cancellation gate can make the continue/pause prompt disappear.
                try {
                    AgentChildRunControl.terminate(session, reason)
                } finally {
                    val accepted = session.requestStop()
                    if (accepted) mainHandler.post {
                        if (!destroyed && overlaySession === session && session.terminalResult == null) {
                            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
                        }
                    }
                }
            }
        }
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        val targets = AgentChildTaskGroups.captureRunStopTargets(runId)
        // Explicit whole-task cancellation is not a terminal network failure or a second choice.
        stopMainRun(runId, AgentChildControlPolicy.Reason.USER_CANCEL)
        retireChildTargets(targets)
    }

    private fun retireChildTargets(targets: List<AgentChildTaskGroups.StopTarget>) {
        if (targets.isEmpty()) return
        childCleanup.execute {
            targets.forEach { captured ->
                runCatching { AgentChildTaskGroups.stop(captured) }.onFailure { failure ->
                    AndroidAgentLogger.error("Child task cleanup failed: type=${failure.safeLogType()}")
                }
            }
        }
    }

    private fun stopIfRuntimeIdle() {
        if (sessions.isEmpty() && pendingStartRequests.isEmpty() && !AgentChildTaskGroups.hasAnyActive() &&
            capsuleView == null && resultCardView == null) stopSelf()
    }

    private fun requestPause() {
        requestPause(overlayRunId.orEmpty())
    }

    private fun requestPause(runId: String) {
        if (runId.isBlank()) return
        val session = sessions.get(runId)?.takeUnless { it.isTerminal || it.controller.isCancelled } ?: return
        AgentChildRunControl.pause(session)
        if (overlaySession === session) {
            state.value = state.value.copy(
                phase = AgentOverlayPhase.PAUSED,
                status = AgentOverlayStatus.Paused,
            )
        }
    }

    private fun requestResume() {
        requestResume(overlayRunId.orEmpty())
    }

    private fun requestCompactRun(
        runId: String,
        keepRecent: Int? = null,
        compressModelConfig: AgentModelClient.ModelConfig? = null,
        childTaskId: String? = null,
    ): Boolean {
        if (runId.isBlank()) return false
        return sessions.get(runId)?.requestCompact(keepRecent, compressModelConfig, childTaskId) ?: false
    }

    private fun requestResume(runId: String) {
        if (runId.isBlank()) return
        val session = sessions.get(runId)?.takeUnless { it.isTerminal || it.controller.isCancelled } ?: return
        AgentChildRunControl.resume(session)
        if (overlaySession === session) {
            state.value = state.value.copy(
                phase = AgentOverlayPhase.RUNNING,
                status = AgentOverlayStatus.Continuing,
            )
        }
    }

    private fun requestSupplementForRun(runId: String, text: String, requestId: String = "", imagesJson: String = "[]") {
        if (runId.isBlank() || sessions.get(runId) == null) return
        requestSupplement(text, runId, requestId, imagesJson)
    }

    private fun requestSupplement(text: String, runId: String? = overlayRunId, requestId: String = "", imagesJson: String = "[]") {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        val targetRunId = runId.orEmpty()
        if (runCatching { io.github.mangi.eta.agent.model.AgentSupplementMedia.persistedImages(imagesJson) }.isFailure) return
        if (requestId.isNotBlank() && synchronized(supplementsLock) {
                supplementsByRunId[targetRunId]?.items?.any { it.requestId == requestId } == true
            }) return
        val targetSession = sessions.get(targetRunId)
        val updatesOverlay = targetSession?.let {
            AgentOverlayVisibilityPolicy.allowsCapsule(it.taskSurfaceMode)
        } ?: true
        if (updatesOverlay) setCapsuleInputMode(focusable = false)
        targetSession?.let { session ->
            val event = session.steer(supplementText, imagesJson) {
                recordSupplementEvent(targetRunId, supplementText, requestId, imagesJson)
            }
            if (event == null) {
                if (!session.isTerminal) {
                    if (updatesOverlay) {
                        state.value = state.value.copy(
                            status = AgentOverlayStatus.Finishing,
                        )
                    }
                    return
                }
            } else {
                AgentChildRunControl.resume(session)
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                if (updatesOverlay) state.value = state.value.applyEvent(event)
                return
            }
        }

        val completed = lastCompletedRunContext ?: return
        if (targetRunId.isNotBlank() && completed.request.runId != targetRunId) return
        if (completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
            requestId = requestId,
            imagesJson = imagesJson,
        )
        startRun(continuationRequest)
    }

    private fun recordSupplementEvent(runId: String, text: String, requestId: String = "", imagesJson: String = "[]"): AgentEvent.UserSupplementReceived {
        val supplement = synchronized(supplementsLock) {
            val extras = supplementsByRunId.getOrPut(runId) { RunSupplements() }
            AgentUiHandoffPayload.Supplement(
                index = extras.nextIndex++,
                text = text,
                requestId = requestId,
                imagesJson = imagesJson,
                createdAt = System.currentTimeMillis(),
            ).also { extras.items += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
            requestId = supplement.requestId,
            imagesJson = supplement.imagesJson,
        )
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun showOverlay() {
        if (capsuleView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // ── 状态胶囊窗口：收起时一句状态，点击展开控制；可自由拖动并记忆位置 ─────
        val capsule = createOverlayComposeView {
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                onToggleExpanded = ::toggleCapsule,
                onCollapsedSettled = ::onCapsuleCollapsedSettled,
                onPause = ::requestPause,
                onResume = ::requestResume,
                onStop = ::requestStop,
                onSupplementModeChange = ::setCapsuleInputMode,
                onSupplement = { text -> requestSupplement(text) },
                onOpenConversation = ::openConversation,
                onOpenHome = ::openHome,
                onDragBy = ::moveCapsuleBy,
                onDragFinished = ::snapCapsuleToEdge,
                onCollapseRequest = ::collapseCapsule,
                recentOpened = state.value.recentOpenedSessions,
                unreadSessions = state.value.unreadSessions,
                collapsedX = capsuleOffsetX.value,
                collapsedY = capsuleOffsetY.value,
                fullScreen = capsuleFullScreen.value,
            )
        }
        savedCapsuleX = Prefs.getInt(PREF_CAPSULE_X, -1)
        savedCapsuleY = Prefs.getInt(PREF_CAPSULE_Y, -1)
        val capsuleLp = capsuleLayoutParams()
        runCatching { wm.addView(capsule, capsuleLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        capsuleView = capsule
        capsuleParams = capsuleLp
        capsuleCollapsedX = capsuleLp.x
        capsuleCollapsedY = capsuleLp.y
        capsuleOffsetX.value = capsuleLp.x
        capsuleOffsetY.value = capsuleLp.y
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        if (expand) {
            capsuleParams?.let {
                capsuleCollapsedX = it.x
                capsuleCollapsedY = it.y
            }
            capsuleFullScreen.value = true
            setCapsuleWindowFullScreen()
        }
        capsuleExpanded.value = expand
    }

    /** 展开态改成全屏透明窗口：面板外点一下就能收起，面板本体仍只占一小块。 */
    private fun setCapsuleWindowFullScreen() {
        val lp = capsuleParams ?: return
        val capsule = capsuleView ?: return
        val wm = windowManager ?: return
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.x = 0
        lp.y = 0
        runCatching { wm.updateViewLayout(capsule, lp) }
    }

    /** 点面板外任意位置：收起大面板（收起动画结束后由 onCapsuleCollapsedSettled 缩窗复位）。 */
    private fun collapseCapsule() {
        if (!capsuleExpanded.value) return
        capsuleExpanded.value = false
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) {
            setCapsuleWindowSize(CAPSULE_COLLAPSED_WIDTH_DP, CAPSULE_COLLAPSED_HEIGHT_DP)
            restoreCapsuleCollapsedPosition()
            capsuleOffsetX.value = capsuleCollapsedX.takeIf { it != Int.MIN_VALUE } ?: capsuleOffsetX.value
            capsuleOffsetY.value = capsuleCollapsedY.takeIf { it != Int.MIN_VALUE } ?: capsuleOffsetY.value
            capsuleFullScreen.value = false
        }
    }

    /** 收起后回到用户拖到的位置（贴边就继续贴边）。 */
    private fun restoreCapsuleCollapsedPosition() {
        val lp = capsuleParams ?: return
        val capsule = capsuleView ?: return
        val wm = windowManager ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        if (capsuleCollapsedX != Int.MIN_VALUE) {
            lp.x = capsuleCollapsedX.coerceIn(0, maxOf(0, screenWidth - lp.width))
        }
        if (capsuleCollapsedY != Int.MIN_VALUE) {
            lp.y = capsuleCollapsedY.coerceIn(0, maxOf(0, screenHeight - lp.height))
        }
        runCatching { wm.updateViewLayout(capsule, lp) }
    }

    private fun setCapsuleWindowSize(widthDp: Int, heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val width = dpToPx(widthDp)
        val height = dpToPx(heightDp)
        if (lp.width == width && lp.height == height) return
        lp.width = width
        lp.height = height
        lp.x = lp.x.coerceIn(0, maxOf(0, resources.displayMetrics.widthPixels - width))
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /** 收起态拖动小胶囊：跟着手指走，超出屏幕会被夹住。 */
    private fun moveCapsuleBy(dx: Float, dy: Float) {
        val lp = capsuleParams ?: return
        val capsule = capsuleView ?: return
        val wm = windowManager ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        lp.x = (lp.x + dx.toInt()).coerceIn(0, maxOf(0, screenWidth - lp.width))
        lp.y = (lp.y + dy.toInt()).coerceIn(0, maxOf(0, screenHeight - lp.height))
        capsuleCollapsedX = lp.x
        capsuleCollapsedY = lp.y
        capsuleOffsetX.value = lp.x
        capsuleOffsetY.value = lp.y
        runCatching { wm.updateViewLayout(capsule, lp) }
    }

    /** 松手贴边：只有靠近左右边缘（屏宽 15% 内）才吸附，放中间就停在中间。 */
    private fun snapCapsuleToEdge() {
        val lp = capsuleParams ?: return
        val capsule = capsuleView ?: return
        val wm = windowManager ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val edgeZone = (screenWidth * 0.15f).toInt()
        val maxX = maxOf(0, screenWidth - lp.width)
        val targetX = when {
            lp.x <= edgeZone -> 0
            lp.x >= maxX - edgeZone -> maxX
            else -> lp.x.coerceIn(0, maxX)
        }
        if (lp.x != targetX) {
            lp.x = targetX
            runCatching { wm.updateViewLayout(capsule, lp) }
        }
        capsuleCollapsedX = lp.x
        capsuleCollapsedY = lp.y
        capsuleOffsetX.value = lp.x
        capsuleOffsetY.value = lp.y
        persistCapsulePosition(lp.x, lp.y)
    }

    /** 记住位置：重启 App 后小胶囊还停在上次的地方。 */
    private fun persistCapsulePosition(x: Int, y: Int) {
        savedCapsuleX = x
        savedCapsuleY = y
        runCatching {
            Prefs.putInt(PREF_CAPSULE_X, x)
            Prefs.putInt(PREF_CAPSULE_Y, y)
        }
    }

    private fun setCapsuleInputMode(focusable: Boolean) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /** 按当前 sessions 同步胶囊用的运行中清单（多会话并发时列表会变长）。 */
    private fun syncRunningSessions() {
        runningSessionInfo.keys.retainAll(sessions.snapshot().map { it.runId }.toSet())
        val list = mutableListOf<AgentOverlayRunningSession>()
        sessions.snapshot().forEach { session ->
            runningSessionInfo[session.runId]?.let { info ->
                list += AgentOverlayRunningSession(
                    runId = info.runId,
                    conversationId = info.conversationId,
                    title = info.title,
                    running = true,
                )
            }
        }
        recentSessionInfo.values.toList().asReversed().forEach { info ->
            if (list.size >= SESSION_LIST_LIMIT) return@forEach
            if (list.any { it.conversationId == info.conversationId }) return@forEach
            list += AgentOverlayRunningSession(
                runId = info.runId,
                conversationId = info.conversationId,
                title = info.title,
                running = false,
            )
        }
        val trimmed = list.take(SESSION_LIST_LIMIT)
        val next = state.value.copy(
            runningSessions = trimmed,
            finishedBadge = unreadFinishedCount,
            recentOpenedSessions = recentOpenedState.value.take(RECENT_OPENED_SHOWN),
            unreadSessions = unreadSessionState.value,
        )
        if (state.value != next) state.value = next
    }

    /** 任务完成后把该会话移进「最近会话」与「最新回复」。 */
    private fun rememberFinishedSession(runId: String) {
        val info = runningSessionInfo[runId] ?: return
        recentSessionInfo.remove(runId)
        recentSessionInfo[runId] = info
        while (recentSessionInfo.size > SESSION_LIST_LIMIT) {
            val oldest = recentSessionInfo.keys.firstOrNull() ?: break
            recentSessionInfo.remove(oldest)
        }
        info.conversationId.takeIf { it.isNotBlank() }?.let { conversationId ->
            unreadSessionIds.remove(conversationId)
            unreadSessionIds.add(conversationId)
            while (unreadSessionIds.size > UNREAD_SESSION_KEEP) {
                val oldestUnread = unreadSessionIds.firstOrNull() ?: break
                unreadSessionIds.remove(oldestUnread)
            }
            unreadFinishedCount = (unreadFinishedCount + 1).coerceAtMost(99)
            refreshUnreadSessions()
        }
    }

    private fun refreshUnreadSessions() {
        unreadSessionState.value = unreadSessionIds.toList().asReversed().map { id ->
            val title = recentSessionInfo.values
                .firstOrNull { it.conversationId == id }
                ?.title
                ?.takeIf { it.isNotBlank() }
            AgentOverlayRunningSession(
                runId = id,
                conversationId = id,
                title = title ?: "会话",
                running = false,
            )
        }
    }

    private fun noteRecentOpened(conversationId: String) {
        if (conversationId.isBlank()) return
        recentOpenedIds.remove(conversationId)
        recentOpenedIds.addFirst(conversationId)
        while (recentOpenedIds.size > RECENT_OPENED_KEEP) recentOpenedIds.removeLast()
        recentOpenedState.value = recentOpenedIds.map { id ->
            val title = recentSessionInfo.values
                .firstOrNull { it.conversationId == id }
                ?.title
                ?.takeIf { it.isNotBlank() }
            AgentOverlayRunningSession(runId = id, conversationId = id, title = title ?: "会话", running = false)
        }
    }

    /** 从悬浮胶囊跳回某个会话：复用 MainActivity 已有的「打开指定会话」入口。 */
    private fun openConversation(conversationId: String) {
        noteRecentOpened(conversationId)
        if (unreadSessionIds.remove(conversationId)) refreshUnreadSessions()
        if (conversationId.isBlank()) return
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION)
                    .putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, conversationId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            capsuleExpanded.value = false
            unreadFinishedCount = (unreadFinishedCount - 1).coerceAtLeast(0)
            if (runningSessionCount() == 0) {
                dismissAndStop()
            } else {
                syncRunningSessions()
            }
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("overlay_open_conversation_failed") {
                "Overlay open conversation failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /** 长按胶囊 / 点「回到 Eta 主页」：打开主界面。 */
    private fun openHome() {
        capsuleExpanded.value = false
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("overlay_open_home_failed") {
                "Overlay open home failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun runningSessionCount(): Int = sessions.snapshot().count { !it.isTerminal }

    private fun showResultCard(wm: WindowManager, session: AgentRuntimeSession) {
        if (overlaySession !== session ||
            !AgentOverlayVisibilityPolicy.shouldShowResultCard(
                session.taskSurfaceMode, hasExecutedForegroundTool,
            )
        ) return
        if (resultCardView != null) return
        val card = createOverlayComposeView {
            AgentResultCard(
                state = state.value,
                onClose = ::dismissAndStop,
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            if (!renderOverlayContent) return@apply
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 的
                    // RuntimeShader 只检查系统版本，因此系统浮层统一使用其普通圆角回退。
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        content()
                    }
                }
            }
        }

    private fun capsuleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(CAPSULE_COLLAPSED_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Capsule"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            gravity = Gravity.LEFT or Gravity.TOP
            val screenWidth = resources.displayMetrics.widthPixels
            val capsuleWidth = dpToPx(CAPSULE_COLLAPSED_WIDTH_DP)
            // 默认位置：顶部居中靠上（照搬第三方默认在上方的行为）；用户拖动后记忆。
            x = if (savedCapsuleX in 0..(screenWidth - capsuleWidth)) savedCapsuleX
                else (screenWidth - capsuleWidth) / 2
            y = if (savedCapsuleY >= 0) savedCapsuleY else dpToPx(CAPSULE_DEFAULT_TOP_MARGIN_DP)
        }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Result"
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(
        finalState: AgentOverlayState,
        keepVisible: Boolean = false,
        session: AgentRuntimeSession,
    ) {
        if (overlaySession !== session) return
        state.value = finalState
        // 把这条会话移进「最近会话 / 最新回复」，胶囊收尾后仍可点回。
        rememberFinishedSession(session.runId)
        runningSessionInfo.remove(session.runId)
        syncRunningSessions()

        if (AgentOverlayVisibilityPolicy.shouldShowResultCard(session.taskSurfaceMode, hasExecutedForegroundTool)) {
            // 前台执行完：撤掉胶囊，改显半屏结果卡片，不自动关闭，用户手动关闭
            removeAmbientWindows()
            windowManager?.let { showResultCard(it, session) }
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else if (runningSessionCount() == 0 && unreadFinishedCount == 0) {
            dismissAndStop()
        } else {
            // 还有其它会话在跑，或有未读完成：胶囊常驻，换一个会话继续占用浮层。
            overlaySession = sessions.snapshot().firstOrNull { !it.isTerminal }
            ensureOverlayVisible()
        }
    }

    private fun removeAmbientWindows() {
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        capsuleParams = null
    }

    private fun dismissAndStop() {
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        resultCardParams = null
        capsuleParams = null
        windowManager = null
        overlaySession = null
        hasExecutedForegroundTool = false
        capsuleExpanded.value = false
        capsuleFullScreen.value = false
        if (sessions.isEmpty() && pendingStartRequests.isEmpty() && !AgentChildTaskGroups.hasAnyActive()) {
            stopSelf()
        }
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    private fun AgentRuntimeWire.RunRequest.withActiveSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return this
        val supplements = synchronized(supplementsLock) {
            supplementsByRunId[runId]?.items?.toList().orEmpty()
        }
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    private fun failPendingStarts(error: String) {
        pendingStartRequests.values.toList().forEach { pending -> failPendingStart(pending, error) }
        pendingStartRequests.clear()
    }

    private fun failPendingStart(pending: PendingStartRequest, error: String) {
        pending.incoming.close()
        sendRequestIngestedTo(pending.replyTo, pending.incoming.request.runId)
        sendResultTo(
            pending.replyTo,
            AgentRuntimeWire.RunResult(
                runId = pending.incoming.request.runId,
                ok = false,
                content = "",
                error = error,
            ),
        )
    }

    private fun claimOverlay(session: AgentRuntimeSession, wantsOverlay: Boolean): Boolean {
        if (!sessions.contains(session) ||
            !AgentOverlayVisibilityPolicy.allowsCapsule(session.taskSurfaceMode)
        ) return false
        val current = overlaySession
        if (current === session) return true
        if (!wantsOverlay) return current == null
        if (current != null && sessions.contains(current) && !current.isTerminal) return false
        overlaySession = session
        return true
    }

    private companion object {
        // Independent of this service's lifecycle: onDestroy must not cancel its own cleanup.
        val childCleanup = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agent-child-cleanup").apply { isDaemon = true }
        }
        const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        // 胶囊几何与会话列表上限（dp / 条数）
        const val CAPSULE_COLLAPSED_WIDTH_DP = 88
        const val CAPSULE_COLLAPSED_HEIGHT_DP = 60
        const val CAPSULE_DEFAULT_TOP_MARGIN_DP = 12
        const val SESSION_LIST_LIMIT = 3
        const val UNREAD_SESSION_KEEP = 3
        const val RECENT_OPENED_KEEP = 5
        const val RECENT_OPENED_SHOWN = 3
        const val PREF_CAPSULE_X = "agent_overlay_capsule_x"
        const val PREF_CAPSULE_Y = "agent_overlay_capsule_y"
        const val HIDE_DELAY_MS = 2_500L
        const val RESULT_REVIEW_DELAY_MS = 120_000L
        const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private data class RunSupplements(
        val conversationId: String = "",
        val items: MutableList<AgentUiHandoffPayload.Supplement> = mutableListOf(),
        var nextIndex: Int = 1,
    )

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
