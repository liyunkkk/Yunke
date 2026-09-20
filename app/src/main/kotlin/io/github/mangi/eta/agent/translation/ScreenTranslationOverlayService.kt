package io.github.mangi.eta.agent.translation

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 屏幕翻译覆盖层服务。
 *
 * 双窗口架构：
 * 1. 全屏穿透单层硬件加速 Canvas 原位渲染层（InPlaceTranslationCanvasView）：
 *    - 1:1 原地抹除背景；
 *    - 二分法字号严格原框拟合（Strict In-place Auto-Fit）；
 *    - 点击穿透（FLAG_NOT_TOUCHABLE），完全不干扰底层交互；
 * 2. 液态玻璃悬浮操作胶囊：
 *    - 深浅色自适应、可拖拽贴边吸附；
 *    - 独立翻译/取消/清除主区域 + 独立关闭按钮。
 */
internal class ScreenTranslationOverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var canvasOverlayView: InPlaceTranslationCanvasView? = null
    private var controlBubble: LinearLayout? = null
    private var bubbleStatusText: TextView? = null
    private var bubbleIcon: TextView? = null
    private var bubbleCloseBtn: TextView? = null
    private var bubbleDivider: View? = null
    private val isAttached = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlay()
            else -> showOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun isDarkMode(): Boolean {
        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun showOverlay() {
        if (isAttached.get()) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            stopSelf()
            return
        }

        // ---------- 窗口 1：全屏穿透单层 Canvas 贴合层 ----------
        val canvasView = InPlaceTranslationCanvasView(this)
        val translationParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "EtaScreenTranslation"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        runCatching { wm.addView(canvasView, translationParams) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("eta_screen_translation_add_failed") {
                    "Screen translation overlay addView failed: type=${throwable.javaClass.simpleName}"
                }
                stopSelf()
                return
            }

        // ---------- 窗口 2：液态玻璃悬浮操作胶囊 ----------
        val density = resources.displayMetrics.density
        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "EtaScreenTranslationBubble"
            val screenWidth = resources.displayMetrics.widthPixels
            x = screenWidth - (110 * density).roundToInt()
            y = (130 * density).roundToInt()
        }

        val bubble = buildLiquidControlBubble(density, wm, bubbleParams)
        runCatching { wm.addView(bubble, bubbleParams) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("eta_screen_translation_bubble_failed") {
                    "Screen translation bubble addView failed: ${throwable.javaClass.simpleName}"
                }
                runCatching { wm.removeView(canvasView) }
                stopSelf()
                return
            }

        windowManager = wm
        canvasOverlayView = canvasView
        controlBubble = bubble
        isAttached.set(true)
        ScreenTranslationController.attachOverlay(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildLiquidControlBubble(
        density: Float,
        wm: WindowManager,
        params: WindowManager.LayoutParams,
    ): LinearLayout {
        val isDark = isDarkMode()
        val bgColor = if (isDark) BUBBLE_BG_DARK else BUBBLE_BG_LIGHT
        val strokeColor = if (isDark) BUBBLE_STROKE_DARK else BUBBLE_STROKE_LIGHT
        val textColor = if (isDark) TEXT_DARK else TEXT_LIGHT
        val subTextColor = if (isDark) SUBTEXT_DARK else SUBTEXT_LIGHT
        val dividerColor = if (isDark) DIVIDER_DARK else DIVIDER_LIGHT

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (6 * density).roundToInt(),
                (4 * density).roundToInt(),
                (6 * density).roundToInt(),
                (4 * density).roundToInt(),
            )
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 24f * density
                setStroke((1f * density).roundToInt(), strokeColor)
            }
            elevation = 10f * density
        }

        val mainActionSection = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (6 * density).roundToInt(),
                (2 * density).roundToInt(),
                (4 * density).roundToInt(),
                (2 * density).roundToInt(),
            )
        }

        val icon = TextView(this).apply {
            text = "文"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, (4 * density).roundToInt(), 0)
        }
        bubbleIcon = icon
        mainActionSection.addView(icon)

        val statusText = TextView(this).apply {
            text = getString(io.github.mangi.eta.R.string.screen_translation_btn_translate)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        bubbleStatusText = statusText
        mainActionSection.addView(statusText)

        bubble.addView(mainActionSection)

        val divider = View(this).apply {
            setBackgroundColor(dividerColor)
        }
        bubbleDivider = divider
        val dividerParams = LinearLayout.LayoutParams(
            (1f * density).roundToInt().coerceAtLeast(1),
            (14 * density).roundToInt(),
        ).apply {
            leftMargin = (4 * density).roundToInt()
            rightMargin = (4 * density).roundToInt()
        }
        bubble.addView(divider, dividerParams)

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(subTextColor)
            setPadding(
                (6 * density).roundToInt(),
                (2 * density).roundToInt(),
                (6 * density).roundToInt(),
                (2 * density).roundToInt(),
            )
        }
        bubbleCloseBtn = closeBtn
        bubble.addView(closeBtn)

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { wm.updateViewLayout(bubble, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val touchX = event.x
                        val dividerLeft = divider.left
                        if (touchX >= dividerLeft - 4 * density) {
                            ScreenTranslationController.stop(applicationContext)
                        } else {
                            if (ScreenTranslationController.hasActiveTranslations() ||
                                ScreenTranslationController.isTranslating()
                            ) {
                                ScreenTranslationController.clearAndStop(applicationContext)
                            } else {
                                ScreenTranslationController.requestRefresh()
                            }
                        }
                    } else {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val bubbleWidth = bubble.width
                        val targetX = if (params.x + bubbleWidth / 2 < screenWidth / 2) {
                            (12 * density).roundToInt()
                        } else {
                            screenWidth - bubbleWidth - (12 * density).roundToInt()
                        }
                        params.x = targetX
                        runCatching { wm.updateViewLayout(bubble, params) }
                    }
                    true
                }
                else -> false
            }
        }

        return bubble
    }

    private fun hideOverlay() {
        val wm = windowManager
        val canvasView = canvasOverlayView
        val bubble = controlBubble
        if (wm != null) {
            if (canvasView != null) runCatching { wm.removeView(canvasView) }
            if (bubble != null) runCatching { wm.removeView(bubble) }
        }
        windowManager = null
        canvasOverlayView = null
        controlBubble = null
        bubbleStatusText = null
        bubbleIcon = null
        bubbleCloseBtn = null
        bubbleDivider = null
        isAttached.set(false)
        ScreenTranslationController.detachOverlay(this)
    }

    internal fun updateStatusText(text: String) {
        bubbleStatusText?.text = text
    }

    internal fun clearBlocks() {
        canvasOverlayView?.clear()
        bubbleStatusText?.text = getString(io.github.mangi.eta.R.string.screen_translation_btn_translate)
    }

    /**
     * 单层 Canvas 硬件加速直绘渲染
     */
    internal fun renderBlocks(blocks: List<ScreenTranslationBlock>) {
        val canvasView = canvasOverlayView ?: return
        canvasView.setBlocks(blocks)
        val validCount = blocks.count { it.translated?.isNotBlank() == true }
        if (validCount > 0) {
            bubbleStatusText?.text = getString(io.github.mangi.eta.R.string.screen_translation_btn_cancel)
        }
    }

    internal companion object {
        const val ACTION_HIDE = "io.github.mangi.eta.agent.translation.HIDE"
        private const val ACTION_SHOW = "io.github.mangi.eta.agent.translation.SHOW"

        // 液态玻璃视觉配色（深浅色自适应）
        private val BUBBLE_BG_DARK = 0xEB1C1C1E.toInt()
        private val BUBBLE_BG_LIGHT = 0xF2F6F7F9.toInt()
        private val BUBBLE_STROKE_DARK = 0x33FFFFFF.toInt()
        private val BUBBLE_STROKE_LIGHT = 0x26000000.toInt()
        private val TEXT_DARK = 0xFFFFFFFF.toInt()
        private val TEXT_LIGHT = 0xFF1D1D1F.toInt()
        private val SUBTEXT_DARK = 0xFF8E8E93.toInt()
        private val SUBTEXT_LIGHT = 0xFF6C6C70.toInt()
        private val DIVIDER_DARK = 0x26FFFFFF.toInt()
        private val DIVIDER_LIGHT = 0x20000000.toInt()

        fun show(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, ScreenTranslationOverlayService::class.java)
                    .setAction(ACTION_SHOW),
            )
        }

        fun hide(context: Context) {
            val intent = Intent(
                context.applicationContext,
                ScreenTranslationOverlayService::class.java,
            ).setAction(ACTION_HIDE)
            context.applicationContext.startService(intent)
        }
    }
}