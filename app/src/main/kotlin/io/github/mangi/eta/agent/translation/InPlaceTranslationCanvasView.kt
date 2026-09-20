package io.github.mangi.eta.agent.translation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 原位像素级无痕 Canvas 覆盖层：
 * 1. 0 边框、0 圆角、0 贴片感：以原底色 1:1 精准抹平底层原文字；
 * 2. 尺寸严格锁定：绝不向外横向扩张破坏原生界面；
 * 3. 二分法字号反算：根据原文字框几何尺寸，动态计算最佳贴合字号与行距；
 * 4. 单层硬件加速直绘：极致流畅，0 额外 View 开销。
 */
internal class InPlaceTranslationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.DITHER_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private var translationBlocks: List<ScreenTranslationBlock> = emptyList()
    private val density = resources.displayMetrics.density

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setBlocks(blocks: List<ScreenTranslationBlock>) {
        this.translationBlocks = blocks
        postInvalidate()
    }

    fun clear() {
        this.translationBlocks = emptyList()
        postInvalidate()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val blocks = translationBlocks
        if (blocks.isEmpty()) return

        val screenW = width.toFloat()
        val screenH = height.toFloat()

        for (block in blocks) {
            val translated = block.translated?.trim() ?: continue
            if (translated.isBlank() || translated.sameTranslationInputAs(block.source)) continue

            val bounds = block.boundsInScreen
            val bLeft = bounds.left.toFloat().coerceAtLeast(0f)
            val bTop = bounds.top.toFloat().coerceAtLeast(0f)
            val bRight = bounds.right.toFloat().coerceAtMost(screenW)
            val bBottom = bounds.bottom.toFloat().coerceAtMost(screenH)

            val boxW = bRight - bLeft
            val boxH = bBottom - bTop
            if (boxW <= 4f || boxH <= 4f) continue

            // 1. 采样背景色并原地抹除原文字（外扩 1px 消除边缘抗锯齿残留）
            val rawBg = block.sampledBgColor ?: 0xFFF7F8FA.toInt()
            val isDark = isColorDark(rawBg)
            erasePaint.color = rawBg

            val eraseRect = RectF(
                (bLeft - 1f).coerceAtLeast(0f),
                (bTop - 1f).coerceAtLeast(0f),
                (bRight + 1f).coerceAtMost(screenW),
                (bBottom + 1f).coerceAtMost(screenH),
            )
            canvas.drawRect(eraseRect, erasePaint)

            // 2. 文字画笔颜色自适应
            val textColor = if (isDark) 0xFFF0F0F2.toInt() else 0xFF151515.toInt()
            textPaint.color = textColor

            // 3. 原位自适应字号与排版计算（Strict In-place Auto-Fit）
            val isSingleLine = boxH <= 32f * density

            if (isSingleLine) {
                // 单行二分查找最佳字号
                var lowSize = 8f * density
                var highSize = (boxH * 0.78f).coerceIn(8f * density, 24f * density)
                var bestSize = lowSize

                for (i in 0..6) {
                    val mid = (lowSize + highSize) / 2f
                    textPaint.textSize = mid
                    val textW = textPaint.measureText(translated)
                    if (textW <= boxW * 1.05f) {
                        bestSize = mid
                        lowSize = mid + 0.5f
                    } else {
                        highSize = mid - 0.5f
                    }
                }

                // 如果单行由于文字过长导致字号过小（< 9.5sp），尝试允许两行优雅折行
                if (bestSize < 9.5f * density && translated.length > 4) {
                    renderMultiLineText(canvas, translated, bLeft, bTop, boxW, boxH, density)
                } else {
                    textPaint.textSize = bestSize
                    val fontMetrics = textPaint.fontMetrics
                    val baseline = bTop + (boxH - (fontMetrics.descent + fontMetrics.ascent)) / 2f
                    canvas.drawText(translated, bLeft, baseline, textPaint)
                }
            } else {
                // 多行段落排版
                renderMultiLineText(canvas, translated, bLeft, bTop, boxW, boxH, density)
            }
        }
    }

    private fun renderMultiLineText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        boxW: Float,
        boxH: Float,
        density: Float,
    ) {
        val targetWidth = boxW.toInt().coerceAtLeast(10)
        var lowSize = 8f * density
        var highSize = (boxH * 0.55f).coerceIn(8f * density, 20f * density)
        var bestSize = lowSize
        var bestLayout: StaticLayout? = null

        for (i in 0..6) {
            val mid = (lowSize + highSize) / 2f
            textPaint.textSize = mid
            val layout = createStaticLayout(text, textPaint, targetWidth)
            if (layout.height <= boxH * 1.15f) {
                bestSize = mid
                bestLayout = layout
                lowSize = mid + 0.5f
            } else {
                highSize = mid - 0.5f
            }
        }

        val finalLayout = bestLayout ?: run {
            textPaint.textSize = bestSize
            createStaticLayout(text, textPaint, targetWidth)
        }

        val offsetY = top + max(0f, (boxH - finalLayout.height) / 2f)
        canvas.save()
        canvas.translate(left, offsetY)
        finalLayout.draw(canvas)
        canvas.restore()
    }

    private fun createStaticLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                1.0f,
                0f,
                false,
            )
        }
    }

    private fun isColorDark(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        return luminance < 145.0
    }
}