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

    /** 缩略帧轮询间隔：约 4 fps，胶囊微缩镜足够顺滑，又不会挤占 GUI 命令。 */
    private const val THUMB_POLL_INTERVAL_MS = 250L
    /** Agent 空闲多久后恢复取帧（秒）。0 表示正在操作，必须让路。 */
    private const val BUSY_IDLE_THRESHOLD_SECONDS = 1L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshots = MutableStateFlow(VirtualDisplaySnapshot.Inactive)
    val snapshots: StateFlow<VirtualDisplaySnapshot> = _snapshots.asStateFlow()

    private var pollJob: kotlinx.coroutines.Job? = null
    private var touchSequence = 0L

    /** 用户是否已在胶囊里开启手动接管。 */
    @Volatile
    private var userControlling = false
    @Volatile
    private var materializing = false

    fun start(context: Context) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { pollOnce(context) }
                delay(THUMB_POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        _snapshots.value = VirtualDisplaySnapshot.Inactive
        userControlling = false
        materializing = false
    }

    fun setUserControlling(controlling: Boolean) {
        userControlling = controlling
        _snapshots.value = _snapshots.value.copy(userControlling = controlling)
    }

    fun setMaterializingToMain(value: Boolean) {
        materializing = value
        _snapshots.value = _snapshots.value.copy(materializingToMain = value)
    }

    private fun pollOnce(context: Context) {
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
        // Agent 正在操作副屏时让路：owner 只有一条连接，抢帧会拖慢 GUI 命令。
        val canFetch = idleSeconds < 0L || idleSeconds >= BUSY_IDLE_THRESHOLD_SECONDS
        val previous = _snapshots.value
        val frame = if (canFetch) fetchThumb() else previous.frame
        val width = frame?.second ?: previous.width
        val height = frame?.third ?: previous.height
        val packageName = previous.packageName
        _snapshots.value = VirtualDisplaySnapshot(
            active = true,
            displayId = displayId,
            width = if (width > 0) width else previous.width,
            height = if (height > 0) height else previous.height,
            appName = previous.appName,
            packageName = packageName,
            userControlling = userControlling,
            materializingToMain = materializing,
            frame = frame?.first ?: previous.frame,
            frameSequence = if (frame != null) previous.frameSequence + 1 else previous.frameSequence,
            touchEffect = previous.touchEffect,
        )
    }

    /** 返回 (ImageBitmap, 原始宽, 原始高)。 */
    private fun fetchThumb(): Triple<androidx.compose.ui.graphics.ImageBitmap, Int, Int>? {
        val payload = runCatching { VirtualDisplaySession.viewerFrame(thumb = true) }.getOrNull() ?: return null
        val bytes = runCatching {
            Base64.decode(payload.optString("data"), Base64.DEFAULT)
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
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
    }
}
