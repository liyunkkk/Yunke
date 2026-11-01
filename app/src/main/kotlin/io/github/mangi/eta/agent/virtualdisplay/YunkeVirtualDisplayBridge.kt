package io.github.mangi.eta.agent.virtualdisplay

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.asImageBitmap
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * YUNKe 副屏 → Eta 胶囊的数据桥。
 *
 * 上层（AgentOverlayCapsule / VirtualDisplayStageCard）只认 [VirtualDisplaySnapshot]；
 * 本类负责把 YUNKe 的 VirtualDisplaySession 状态与缩略帧，翻译成这个契约。
 *
 * 帧链路仍然是 owner 的 pull 模型（跨进程 + 单连接），因此这里做限频轮询：
 * 缩略帧走 owner 的低分辨率 JPEG 通道（长边 360），单帧字节数远小于全分辨率 PNG。
 * Agent 有 GUI 活动时（idle_seconds == 0）主动让路，避免和 executeGui 抢 owner 连接。
 */
internal object YunkeVirtualDisplayBridge {

    /**
     * 取帧间隔。
     *
     * 关键约束：VirtualDisplaySession.viewerFrame 与 executeGui 是同一个对象上的
     * @Synchronized，共用一把监视器锁，而且每次取帧都是一次完整的 LocalSocket 往返。
     * 所以取帧频率直接决定 Agent 的 GUI 调用要排多久队。
     *
     * 早期用 250ms 硬轮询，等于把锁的一半时间占住，observe_screen/tap 被拖慢到
     * 触发「无进展」守卫，任务直接失败。这里改成三档：
     * - 胶囊不可见：完全不取帧（连锁都不碰）
     * - Agent 刚操作过（idle < 阈值）：低频让路
     * - Agent 空闲：正常刷新
     */
    private const val INTERVAL_VISIBLE_IDLE_MS = 400L
    private const val INTERVAL_VISIBLE_BUSY_MS = 1_600L
    private const val INTERVAL_EXPANDED_MS = 1_000L
    /** Agent 空闲多久算「不在操作」（秒）。 */
    private const val BUSY_IDLE_THRESHOLD_SECONDS = 2L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshots = MutableStateFlow(VirtualDisplaySnapshot.Inactive)
    val snapshots: StateFlow<VirtualDisplaySnapshot> = _snapshots.asStateFlow()

    private var pollJob: kotlinx.coroutines.Job? = null
    private var touchSequence = 0L
    @Volatile
    private var lastFrameFetchMs = 0L
    /**
     * 浮层当前是否可见。
     *
     * 不可见时完全不取帧：既省电，更重要的是不去抢 executeGui 的锁。
     * 之前 removeAmbientWindows 之后桥仍在 250ms 轮询，等于在没有 UI 的情况下
     * 持续占用 owner 连接，任务和界面都会变慢。
     */
    @Volatile
    private var overlayVisible = false

    /** 用户是否已在胶囊里开启手动接管。 */
    @Volatile
    private var userControlling = false
    /**
     * 胶囊是否处于展开态（装裱视窗可见）。
     *
     * 展开时窗口宽 324dp ≈ 891px，360px 缩略帧铺满会放大 2.5 倍、肉眼发糊，
     * 因此展开时改取长边 900 的 stage 档；收起成微缩镜时仍用 360 省带宽。
     */
    @Volatile
    private var stageExpanded = false
    @Volatile
    private var materializing = false

    fun start(context: Context) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { pollOnce(context) }
                // 固定小睡即可：真正的取帧节奏由 pollOnce 内的分档间隔决定，
                // 不可见时它会直接返回，不产生任何 owner 往返。
                delay(200L)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        // 保证状态不残留：任何 stop 之后都不再取帧，不依赖调用方先调 setOverlayVisible(false)。
        overlayVisible = false
        _snapshots.value = VirtualDisplaySnapshot.Inactive
        userControlling = false
        materializing = false
    }

    fun setUserControlling(controlling: Boolean) {
        userControlling = controlling
        _snapshots.value = _snapshots.value.copy(userControlling = controlling)
    }

    /**
     * 浮层显示/隐藏时切换取帧开关。
     *
     * 隐藏时必须立刻停止取帧：owner 只有一条连接，空转的预览请求会和
     * executeGui 抢同一把锁，把任务拖慢到触发「无进展」。
     */
    fun setOverlayVisible(visible: Boolean) {
        if (overlayVisible == visible) return
        overlayVisible = visible
        if (visible) {
            // 重新可见时立刻取一帧，避免先显示空框。
            lastFrameFetchMs = 0L
        } else if (_snapshots.value.active) {
            // 隐藏时立刻把快照置为非活跃。
            // 否则轮询停摆后没人再更新状态：副屏随后被 owner 释放/超时回收时，
            // 快照会永远停在 active=true + 旧帧，胶囊就会为一个已经不存在的副屏
            // 保留一张冻结的微缩镜，看起来正是「画面卡住不动」。
            _snapshots.value = VirtualDisplaySnapshot.Inactive
        }
    }

    /** 展开/收起时切换取帧画质档位。 */
    fun setStageExpanded(expanded: Boolean) {
        if (stageExpanded == expanded) return
        stageExpanded = expanded
        // 立即作废上一次取帧时间，让下一轮立刻拿到新档位的画面。
        lastFrameFetchMs = 0L
    }

    fun setMaterializingToMain(value: Boolean) {
        materializing = value
        _snapshots.value = _snapshots.value.copy(materializingToMain = value)
    }

    private fun pollOnce(context: Context) {
        // 浮层不可见时直接返回：连 viewerStatus（同样是 @Synchronized）都不调，
        // 彻底不碰 VirtualDisplaySession 的监视器锁。
        if (!overlayVisible) return
        val status = runCatching { VirtualDisplaySession.viewerStatus() }.getOrNull()
        val running = status?.optBoolean("running") == true
        if (!running) {
            if (_snapshots.value.active) {
                _snapshots.value = VirtualDisplaySnapshot.Inactive
            }
            return
        }
        val displayId = if (status != null && !status.isNull("display_id")) {
            status.optInt("display_id", android.view.Display.DEFAULT_DISPLAY)
        } else {
            android.view.Display.DEFAULT_DISPLAY
        }
        val idleSeconds = if (status != null && !status.isNull("idle_seconds")) {
            status.optLong("idle_seconds", -1L)
        } else {
            -1L
        }
        // 分档节流：Agent 刚操作过就让路（它正在排队等这把锁），空闲时正常刷新。
        val busy = idleSeconds in 0L until BUSY_IDLE_THRESHOLD_SECONDS
        val minInterval = when {
            busy -> INTERVAL_VISIBLE_BUSY_MS
            stageExpanded -> INTERVAL_EXPANDED_MS
            else -> INTERVAL_VISIBLE_IDLE_MS
        }
        val sinceLast = android.os.SystemClock.elapsedRealtime() - lastFrameFetchMs
        val canFetch = sinceLast >= minInterval
        val previous = _snapshots.value
        // GUI 调用在跑或排队时整帧跳过：既不碰锁，也不推进取帧时间戳，
        // 否则「刚好撞上 GUI 的那一次」会白白吃掉整个间隔窗，画面变迟钝。
        val canFetchNow = canFetch && !VirtualDisplaySession.guiBusy()
        val fetched = if (canFetchNow) {
            lastFrameFetchMs = android.os.SystemClock.elapsedRealtime()
            fetchThumb()
        } else {
            null
        }
        val frameBitmap = fetched?.first ?: previous.frame
        val frameWidth = fetched?.second?.takeIf { it > 0 } ?: previous.width
        val frameHeight = fetched?.third?.takeIf { it > 0 } ?: previous.height
        _snapshots.value = VirtualDisplaySnapshot(
            active = true,
            displayId = displayId,
            width = frameWidth,
            height = frameHeight,
            appName = previous.appName,
            packageName = previous.packageName,
            userControlling = userControlling,
            materializingToMain = materializing,
            frame = frameBitmap,
            frameSequence = if (fetched != null) previous.frameSequence + 1 else previous.frameSequence,
            touchEffect = previous.touchEffect,
        )
    }

    /** 最近一次取帧失败原因，供诊断；成功时清空。 */
    @Volatile
    var lastFrameError: String = ""
        private set

    /** 返回 (ImageBitmap, 原始宽, 原始高)。 */
    private fun fetchThumb(): Triple<androidx.compose.ui.graphics.ImageBitmap, Int, Int>? {
        // 用 tryViewerFrame：有 GUI 调用在跑时直接放弃这一帧，绝不去抢那把锁。
        val payload = runCatching {
            if (stageExpanded) {
                VirtualDisplaySession.tryViewerFrame(stage = true)
            } else {
                VirtualDisplaySession.tryViewerFrame(thumb = true)
            }
        }.getOrNull()
        if (payload == null) {
            // 主动让路（GUI 正在用 owner）不是故障，别污染诊断字段。
            lastFrameError = if (VirtualDisplaySession.guiBusy()) lastFrameError else "viewer_frame_null"
            return null
        }
        val bytes = runCatching {
            Base64.decode(payload.optString("data"), Base64.DEFAULT)
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            lastFrameError = "decode_base64_empty"
            return null
        }
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        if (bitmap == null) {
            lastFrameError = "bitmap_decode_failed"
            return null
        }
        lastFrameError = ""
        return Triple(
            bitmap.asImageBitmap(),
            payload.optInt("width", bitmap.width),
            payload.optInt("height", bitmap.height),
        )
    }

    // ── 手动接管：把胶囊画布上的归一化手势派发到副屏 ───────────────────────

    fun dispatchUserTapOnCanvas(normalizedX: Float, normalizedY: Float, longPress: Boolean) {
        val snapshot = _snapshots.value
        if (!snapshot.active) return
        val x = (normalizedX.coerceIn(0f, 1f) * snapshot.width).toFloat()
        val y = (normalizedY.coerceIn(0f, 1f) * snapshot.height).toFloat()
        emitTouchEffect(
            VirtualTouchEffect.Tap(
                sequence = ++touchSequence,
                createdAtMs = System.currentTimeMillis(),
                normalizedX = normalizedX.coerceIn(0f, 1f),
                normalizedY = normalizedY.coerceIn(0f, 1f),
                longPress = longPress,
            ),
        )
        val service = AgentAccessibilityService.current() ?: return
        scope.launch {
            runCatching {
                service.gestureTap(x, y, if (longPress) 600L else 50L, snapshot.displayId)
            }
        }
    }

    fun dispatchUserSwipeOnCanvas(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int,
    ) {
        val snapshot = _snapshots.value
        if (!snapshot.active) return
        val sx = (startX.coerceIn(0f, 1f) * snapshot.width).toFloat()
        val sy = (startY.coerceIn(0f, 1f) * snapshot.height).toFloat()
        val ex = (endX.coerceIn(0f, 1f) * snapshot.width).toFloat()
        val ey = (endY.coerceIn(0f, 1f) * snapshot.height).toFloat()
        emitTouchEffect(
            VirtualTouchEffect.Swipe(
                sequence = ++touchSequence,
                createdAtMs = System.currentTimeMillis(),
                startX = startX.coerceIn(0f, 1f),
                startY = startY.coerceIn(0f, 1f),
                endX = endX.coerceIn(0f, 1f),
                endY = endY.coerceIn(0f, 1f),
                durationMs = durationMs,
            ),
        )
        val service = AgentAccessibilityService.current() ?: return
        scope.launch {
            runCatching {
                service.gestureSwipe(sx, sy, ex, ey, durationMs.toLong(), snapshot.displayId)
            }
        }
    }

    private fun emitTouchEffect(effect: VirtualTouchEffect) {
        _snapshots.value = _snapshots.value.copy(touchEffect = effect)
    }

    /** 供调试/诊断：当前快照摘要。 */
    fun describe(): JSONObject = JSONObject().apply {
        val snapshot = _snapshots.value
        put("active", snapshot.active)
        put("display_id", snapshot.displayId)
        put("frame_sequence", snapshot.frameSequence)
        put("user_controlling", snapshot.userControlling)
        put("last_frame_error", lastFrameError)
    }
}
