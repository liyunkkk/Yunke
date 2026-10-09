package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 副屏悬浮小窗（对齐 PR#142 的 viewer-collapsed 形态）：在其它应用之上持续显示副屏画面与状态。
 *
 * 只读窗口：不接收任何输入，也不改变副屏的观察状态。建窗沿用本应用既有的覆盖层做法——
 * 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（不需要额外权限），否则回退
 * TYPE_APPLICATION_OVERLAY 并要求 canDrawOverlays。刷新 10 秒一次，取帧按 1/4 降采样
 * （全分辨率解码每 2 秒一次会与副屏 GUI 操作抢 owner 的单条连接）；检测到 Agent 刚有活动
 * （idle_seconds == 0）时跳过取帧，避免与 observe_screen 争抢 owner 的单条连接。
 */
internal object VirtualDisplayFloatingWindow {
    private const val REFRESH_SECONDS = 10L
    /** 取帧降采样倍数：小窗只有 150dp 宽，1/4 解码足够看清，开销降到约 1/16。 */
    private const val FRAME_SAMPLE_SIZE = 4
    private const val WINDOW_WIDTH_DP = 150
    private const val WINDOW_HEIGHT_DP = 300

    private var windowView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var frameView: ImageView? = null
    private var statusView: TextView? = null
    private var lastFrameBitmap: android.graphics.Bitmap? = null
    private var refresh: java.util.concurrent.ScheduledExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isVisible(): Boolean = windowView != null

    /** 返回 false 表示没有建窗（缺权限或系统拒绝），调用方据此提示用户。 */
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // WindowManager 只能在有 Looper 的线程上建窗；后台调用统一转投主线程。
            mainHandler.post { runCatching { show(appContext) } }
            return true
        }
        if (windowView != null) return true
        // TYPE_ACCESSIBILITY_OVERLAY 必须用无障碍服务自己当 Context（与 GestureIndicator 同一做法）；
        // 用 applicationContext 建这种窗会被 WindowManager 直接拒绝。
        val service = AgentAccessibilityService.current()
        val overlayContext: Context = service ?: appContext
        val manager = overlayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        if (service == null && !Settings.canDrawOverlays(appContext)) {
            AndroidAgentLogger.warn("Virtual display floating window denied: no overlay permission")
            return false
        }
        val density = overlayContext.resources.displayMetrics.density
        val width = (WINDOW_WIDTH_DP * density).toInt()
        val height = (WINDOW_HEIGHT_DP * density).toInt()
        val root = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 16, 16, 20))
            setPadding(8, 8, 8, 8)
        }
        val frame = ImageView(overlayContext).apply {
            layoutParams = LinearLayout.LayoutParams(width, height - (28 * density).toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val status = TextView(overlayContext).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(frame)
        root.addView(status)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (service != null) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (240 * density).toInt()
        }
        attachDrag(root, params, manager, density)
        val attached = runCatching { manager.addView(root, params) }
        if (attached.isFailure) {
            val failure = attached.exceptionOrNull()
            AndroidAgentLogger.warn(
                "Virtual display floating window attach failed: " +
                    "${failure?.javaClass?.simpleName} ${failure?.message?.take(120).orEmpty()}",
            )
            return false
        }
        windowView = root
        layoutParams = params
        frameView = frame
        statusView = status
        status.text = appContext.getString(R.string.vd_float_none)
        startRefresh(appContext, manager)
        return true
    }

    fun hide(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { runCatching { hide(context.applicationContext) } }
            return
        }
        stopRefresh()
        val view = windowView ?: return
        val manager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { manager?.removeView(view) }
        windowView = null
        layoutParams = null
        frameView = null
        statusView = null
        lastFrameBitmap = null
    }

    /** 窗口内按下拖动：只更新窗口位置，不接收点击之外的输入。 */
    private fun attachDrag(
        root: View,
        params: WindowManager.LayoutParams,
        manager: WindowManager,
        density: Float,
    ) {
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = originX + (event.rawX - downX).toInt()
                    params.y = originY + (event.rawY - downY).toInt()
                    runCatching { manager.updateViewLayout(root, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 松手时把窗口收回屏幕内，避免拖出边界后找不回来。
                    val metrics = root.resources.displayMetrics
                    val maxX = (metrics.widthPixels - WINDOW_WIDTH_DP * density).toInt().coerceAtLeast(0)
                    val maxY = (metrics.heightPixels - WINDOW_HEIGHT_DP * density).toInt().coerceAtLeast(0)
                    params.x = params.x.coerceIn(0, maxX)
                    params.y = params.y.coerceIn(0, maxY)
                    runCatching { manager.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun startRefresh(context: Context, manager: WindowManager) {
        stopRefresh()
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "eta-vd-float").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            {
                runCatching { refreshOnce(context, manager) }
                    .onFailure { AndroidAgentLogger.warn("Virtual display floating window refresh failed: ${it.javaClass.simpleName}") }
            },
            REFRESH_SECONDS,
            REFRESH_SECONDS,
            TimeUnit.SECONDS,
        )
        refresh = executor
    }

    private fun stopRefresh() {
        refresh?.shutdownNow()
        refresh = null
    }

    private fun refreshOnce(context: Context, manager: WindowManager) {
        val status = runCatching { VirtualDisplaySession.viewerStatus() }.getOrNull()
        val running = status?.optBoolean("running") == true
        val idleSeconds = if (status != null && !status.isNull("idle_seconds")) status.optLong("idle_seconds") else -1L
        val label = if (running) {
            context.getString(R.string.vd_float_running, status?.optInt("display_id", 0) ?: 0, idleSeconds.coerceAtLeast(0))
        } else {
            context.getString(R.string.vd_float_none)
        }
        // Agent 刚有活动时不取帧，避免和 observe_screen 抢 owner 的单条连接。
        val fetched = if (running && idleSeconds != 0L) {
            runCatching { VirtualDisplaySession.viewerFrame() }.getOrNull()?.let { payload ->
                val bytes = runCatching { Base64.decode(payload.optString("data"), Base64.DEFAULT) }.getOrNull()
                bytes?.let {
                    val options = BitmapFactory.Options().apply { inSampleSize = FRAME_SAMPLE_SIZE }
                    BitmapFactory.decodeByteArray(it, 0, it.size, options)
                }
            }
        } else {
            null
        }
        // 取帧失败（或正在抢锁）时保留上一帧，避免小窗闪成空白。
        if (fetched != null) lastFrameBitmap = fetched
        val bitmap = fetched ?: lastFrameBitmap
        if (windowView == null) return
        mainHandler.post {
            statusView?.text = label
            if (bitmap != null) frameView?.setImageBitmap(bitmap)
        }
        // 会话已结束时把窗口自动收掉，避免留下过期小窗。
        if (status != null && !running) {
            mainHandler.post { hide(context) }
        }
    }
}
