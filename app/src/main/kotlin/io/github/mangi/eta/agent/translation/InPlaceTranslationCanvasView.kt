package io.github.mangi.eta.agent.translation

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
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

/** 渲染模式：REPLACE = 原位替换原文；BILINGUAL = 不擦原文，在下方叠加译文（兜底对照）。 */
internal enum class RenderMode { REPLACE, BILINGUAL }

/**
 * 原位像素级无痕 Canvas 覆盖层：
 * 1. 背景重建：优先把原文字框的背景像素块 1:1 画回，只有拿不到时才回退单色平涂；
 * 2. 样式继承：颜色继承原文笔画色，字号以 OCR 估算值为先，粗体尽量还原；
 * 3. 尺寸严格锁定：绝不向外横向扩张破坏原生界面，放不下才按比例缩小字号；
 * 4. 双语对照模式：不擦原文，在文字框下方（空间不足时上方）叠加半透明译文条。
 */
internal class InPlaceTranslationCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 背景块贴回时开启双线性过滤，避免缩放产生锯齿。 */
    private val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val bilingualBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BILINGUAL_BG
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.DITHER_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    /** 复用的临时矩形，避免 onDraw 每帧分配。 */
    private val scratchRect = RectF()

    private var translationBlocks: List<ScreenTranslationBlock> = emptyList()
    private val density = resources.displayMetrics.density
    private val minTextSizePx = 7f * density

    /** 系统深浅色：节点兜底路径没有像素底色，用它选近白/近黑底。 */
    private val isNight: Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 当前渲染模式；只读暴露给控制气泡同步按钮文案。 */
    var renderMode: RenderMode = RenderMode.REPLACE
        private set

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setBlocks(blocks: List<ScreenTranslationBlock>) {
        this.translationBlocks = blocks
        postInvalidate()
    }

    fun setRenderMode(mode: RenderMode) {
        if (renderMode != mode) {
            renderMode = mode
            postInvalidate()
        }
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

            when (renderMode) {
                RenderMode.REPLACE ->
                    drawReplace(canvas, block, translated, bLeft, bTop, bRight, bBottom, boxW, boxH, screenW, screenH)
                RenderMode.BILINGUAL ->
                    drawBilingual(canvas, block, translated, bLeft, bTop, bBottom, boxW, screenW, screenH)
            }
        }
    }

    /** 原位替换：先重建背景，再以继承样式绘制译文。 */
    private fun drawReplace(
        canvas: Canvas,
        block: ScreenTranslationBlock,
        translated: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        boxW: Float,
        boxH: Float,
        screenW: Float,
        screenH: Float,
    ) {
        // 1. 背景重建：优先贴回原背景块（外扩 1px 消除边缘抗锯齿残留），否则单色平涂。
        val patch = block.backgroundPatch
        scratchRect.set(
            (left - 1f).coerceAtLeast(0f),
            (top - 1f).coerceAtLeast(0f),
            (right + 1f).coerceAtMost(screenW),
            (bottom + 1f).coerceAtMost(screenH),
        )
        if (patch != null && !patch.isRecycled && patch.width > 0 && patch.height > 0) {
            canvas.drawBitmap(patch, null, scratchRect, patchPaint)
        } else {
            erasePaint.color = block.sampledBgColor ?: defaultBackgroundColor()
            canvas.drawRect(scratchRect, erasePaint)
        }

        // 2. 样式继承：颜色优先用原文笔画色，字重按估计切换。
        val bg = block.sampledBgColor ?: defaultBackgroundColor()
        textPaint.color = block.foregroundColor
            ?: if (isColorDark(bg)) DARK_TEXT else LIGHT_TEXT
        textPaint.typeface = if (block.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val estimated = block.estimatedTextSizePx
            ?.takeIf { it > 0f }
            ?.coerceIn(minTextSizePx, boxH.coerceAtLeast(minTextSizePx))

        // 3. 原位自适应排版：以估算字号为上限（绝不因 box 很高而放大），放不下才二分缩小。
        val isSingleLine = boxH <= 32f * density
        if (isSingleLine) {
            val highSize = (estimated ?: (boxH * 0.78f))
                .coerceIn(minTextSizePx, boxH.coerceAtLeast(minTextSizePx))
            val bestSize = fitSingleLineSize(translated, boxW, highSize)
            if (bestSize < 9.5f * density && translated.length > 4) {
                renderMultiLineText(canvas, translated, left, top, boxW, boxH, estimated)
            } else {
                textPaint.textSize = bestSize
                val fontMetrics = textPaint.fontMetrics
                val baseline = top + (boxH - (fontMetrics.descent + fontMetrics.ascent)) / 2f
                canvas.drawText(translated, left, baseline, textPaint)
            }
        } else {
            renderMultiLineText(canvas, translated, left, top, boxW, boxH, estimated)
        }
    }

    /** 双语对照：不擦原文，在文字框下方（空间不足时上方）叠加半透明译文条。 */
    private fun drawBilingual(
        canvas: Canvas,
        block: ScreenTranslationBlock,
        translated: String,
        left: Float,
        top: Float,
        bottom: Float,
        boxW: Float,
        screenW: Float,
        screenH: Float,
    ) {
        val targetWidth = boxW.toInt().coerceAtLeast(24)
        textPaint.color = BILINGUAL_TEXT
        textPaint.typeface = if (block.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        var size = (block.estimatedTextSizePx ?: (14f * density))
            .coerceIn(minTextSizePx, 28f * density)
        textPaint.textSize = size
        var layout = createStaticLayout(translated, textPaint, targetWidth)
        val maxHeight = size * 1.4f * 4f
        var guard = 0
        while (layout.height > maxHeight && size > minTextSizePx && guard++ < 8) {
            size = (size * 0.85f).coerceAtLeast(minTextSizePx)
            textPaint.textSize = size
            layout = createStaticLayout(translated, textPaint, targetWidth)
        }

        val padH = 8f * density
        val padV = 5f * density
        val bgW = layout.width + padH * 2f
        val bgH = layout.height + padV * 2f
        val gap = 2f * density
        // 默认画在框下方；下方空间不足则画到框上方。
        var blockTop = bottom + gap
        if (blockTop + bgH > screenH) blockTop = top - gap - bgH
        blockTop = blockTop.coerceIn(0f, (screenH - bgH).coerceAtLeast(0f))
        val blockLeft = left.coerceIn(0f, (screenW - bgW).coerceAtLeast(0f))

        scratchRect.set(blockLeft, blockTop, blockLeft + bgW, blockTop + bgH)
        val corner = 6f * density
        canvas.drawRoundRect(scratchRect, corner, corner, bilingualBgPaint)

        canvas.save()
        canvas.translate(blockLeft + padH, blockTop + padV)
        layout.draw(canvas)
        canvas.restore()
    }

    /** 单行二分：在 [minTextSizePx, highSize] 内找最大可放下译文的字号。 */
    private fun fitSingleLineSize(text: String, boxW: Float, highSize: Float): Float {
        var low = minTextSizePx
        var high = highSize.coerceAtLeast(minTextSizePx)
        var best = low
        for (i in 0..6) {
            val mid = (low + high) / 2f
            textPaint.textSize = mid
            if (textPaint.measureText(text) <= boxW * 1.05f) {
                best = mid
                low = mid + 0.5f
            } else {
                high = mid - 0.5f
            }
        }
        return best
    }

    private fun renderMultiLineText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        boxW: Float,
        boxH: Float,
        estimatedSize: Float?,
    ) {
        val targetWidth = boxW.toInt().coerceAtLeast(10)
        var lowSize = minTextSizePx
        var highSize = (estimatedSize ?: (boxH * 0.55f))
            .coerceIn(minTextSizePx, boxH.coerceAtLeast(minTextSizePx))
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

    /**
     * 节点兜底路径拿不到像素底色（sampledBgColor/is backgroundPatch 都为空）时的回退：
     * 跟随系统深浅色给近白/近黑底，而不是写死的浅色，避免深色界面上出现突兀白块。
     */
    private fun defaultBackgroundColor(): Int = if (isNight) NIGHT_BG else DAY_BG

    private companion object {
        private val DAY_BG = 0xFFF7F8FA.toInt()
        private val NIGHT_BG = 0xFF1C1C1E.toInt()
        private val LIGHT_TEXT = 0xFF151515.toInt()
        private val DARK_TEXT = 0xFFF0F0F2.toInt()
        private val BILINGUAL_BG = 0xCC000000.toInt()
        private val BILINGUAL_TEXT = 0xFFFFFFFF.toInt()
    }
}
