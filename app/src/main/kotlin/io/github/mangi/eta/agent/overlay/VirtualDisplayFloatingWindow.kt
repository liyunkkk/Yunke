package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
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
 *
 * 外形与系统小窗一致：整体圆角、可拖动；点状态行右侧的「–」收成贴边气泡，
 * 点气泡再展开。收起期间不再取帧，省电也少抢 owner 连接。
 */
internal object VirtualDisplayFloatingWindow {
    private const val REFRESH_SECONDS = 10L
    /** 取帧降采样倍数：小窗只有 150dp 宽，1/4 解码足够看清，开销降到约 1/16。 */
    private const val FRAME_SAMPLE_SIZE = 4
    private const val WINDOW_WIDTH_DP = 150
    private const val WINDOW_HEIGHT_DP = 300
    private const val BUBBLE_DP = 52
    private const val CORNER_DP = 16
    private const val STATUS_ROW_DP = 28
    /** 判定「点」而不是「拖」的位移阈值。 */
    private const val DRAG_SLOP_DP = 8

    private var container: FrameLayout? = null
    private var expanded: LinearLayout? = null
    private var bubble: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var frameView: ImageView? = null
    private var statusView: TextView? = null
    private var lastFrameBitmap: android.graphics.Bitmap? = null
    private var collapsed = false
    private var refresh: java.util.concurrent.ScheduledExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isVisible(): Boolean = container != null

    /** 返回 false 表示没有建窗（缺权限或系统拒绝），调用方据此提示用户。 */
    fun show(context: Context): Boolean {
        val appContext = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // WindowManager 只能在有 Looper 的线程上建窗；后台调用统一转投主线程。
            mainHandler.post { runCatching { show(appContext) } }
            return true
        }
        if (container != null) return true
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
        val statusRow = (STATUS_ROW_DP * density).toInt()
        val bubbleSize = (BUBBLE_DP * density).toInt()
        val corner = CORNER_DP * density

        val frame = ImageView(overlayContext).apply {
            layoutParams = LinearLayout.LayoutParams(width, height - statusRow)
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipToOutline = true
            outlineProvider = roundOutline(corner)
        }
        val status = TextView(overlayContext).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 收起按钮：一个不抢眼的小横条，点一下变成贴边气泡。
        val collapseButton = TextView(overlayContext).apply {
            text = "–"
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { setCollapsed(overlayContext, manager, true) }
        }
        val statusRowView = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status)
            addView(collapseButton)
        }
        val content = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(corner, Color.argb(214, 16, 16, 20))
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            addView(frame)
            addView(statusRowView)
        }
        val bubbleView = TextView(overlayContext).apply {
            text = overlayContext.getString(R.string.vd_float_bubble)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(232, 16, 16, 20))
                setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.argb(120, 255, 255, 255))
            }
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            visibility = View.GONE
        }
        val root = FrameLayout(overlayContext).apply {
            addView(content)
            addView(bubbleView)
        }
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
        attachDrag(root, content, params, manager, density, bubbleSize, width, height, null)
        attachDrag(root, bubbleView, params, manager, density, bubbleSize, width, height) {
            setCollapsed(overlayContext, manager, false)
        }
        val attached = runCatching { manager.addView(root, params) }
        if (attached.isFailure) {
            val failure = attached.exceptionOrNull()
            AndroidAgentLogger.warn(
                "Virtual display floating window attach failed: " +
                    "${failure?.javaClass?.simpleName} ${failure?.message?.take(120).orEmpty()}",
            )
            return false
        }
        container = root
        expanded = content
        bubble = bubbleView
        layoutParams = params
        frameView = frame
        statusView = status
        collapsed = false
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
        val view = container ?: return
        val manager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { manager?.removeView(view) }
        container = null
        expanded = null
        bubble = null
        layoutParams = null
        frameView = null
        statusView = null
        lastFrameBitmap = null
        collapsed = false
    }

    /** 收起 / 展开：收起时贴到最近的一侧边缘，展开时贴回屏内。 */
    private fun setCollapsed(context: Context, manager: WindowManager, collapse: Boolean) {
        val root = container ?: return
        val params = layoutParams ?: return
        val content = expanded ?: return
        val bubbleView = bubble ?: return
        if (collapsed == collapse) return
        collapsed = collapse
        val density = context.resources.displayMetrics.density
        val metrics = context.resources.displayMetrics
        val bubbleSize = (BUBBLE_DP * density).toInt()
        content.visibility = if (collapse) View.GONE else View.VISIBLE
        bubbleView.visibility = if (collapse) View.VISIBLE else View.GONE
        if (collapse) {
            params.width = bubbleSize
            params.height = bubbleSize
            val rightEdge = params.x + bubbleSize / 2 >= metrics.widthPixels / 2
            params.x = if (rightEdge) (metrics.widthPixels - bubbleSize).coerceAtLeast(0) else 0
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            val maxX = (metrics.widthPixels - (WINDOW_WIDTH_DP * density).toInt()).coerceAtLeast(0)
            params.x = params.x.coerceIn(0, maxX)
        }
        runCatching { manager.updateViewLayout(root, params) }
    }

    /** 窗口内按下拖动：只更新窗口位置；位移不超过阈值算点击，交给 onTap。 */
    private fun attachDrag(
        root: View,
        target: View,
        params: WindowManager.LayoutParams,
        manager: WindowManager,
        density: Float,
        bubbleSize: Int,
        windowWidth: Int,
        windowHeight: Int,
        onTap: (() -> Unit)?,
    ) {
        val slop = DRAG_SLOP_DP * density
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragged = false
        target.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragged && kotlin.math.abs(dx) + kotlin.math.abs(dy) > slop) dragged = true
                    if (dragged) {
                        params.x = originX + dx.toInt()
                        params.y = originY + dy.toInt()
                        runCatching { manager.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        onTap?.invoke()
                        return@setOnTouchListener true
                    }
                    // 松手时把窗口收回屏幕内，避免拖出边界后找不回来；气泡额外贴到最近一侧。
                    val metrics = target.resources.displayMetrics
                    if (collapsed) {
                        params.x = if (params.x + bubbleSize / 2 >= metrics.widthPixels / 2) {
                            (metrics.widthPixels - bubbleSize).coerceAtLeast(0)
                        } else {
                            0
                        }
                    } else {
                        val maxX = (metrics.widthPixels - windowWidth).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - windowHeight).coerceAtLeast(0)
                        params.x = params.x.coerceIn(0, maxX)
                        params.y = params.y.coerceIn(0, maxY)
                    }
                    runCatching { manager.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun roundedBackground(radiusPx: Float, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fill)
        }

    private fun roundOutline(radiusPx: Float): ViewOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
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
        // Agent 刚有活动时不取帧，避免和 observe_screen 抢 owner 的单条连接；收起成气泡时也不取帧。
        val fetched = if (running && idleSeconds != 0L && !collapsed) {
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
        if (container == null) return
        mainHandler.post {
            statusView?.text = label
            if (bitmap != null && !collapsed) frameView?.setImageBitmap(bitmap)
        }
        // 会话已结束时把窗口自动收掉，避免留下过期小窗。
        if (status != null && !running) {
            mainHandler.post { hide(context) }
        }
    }
}
