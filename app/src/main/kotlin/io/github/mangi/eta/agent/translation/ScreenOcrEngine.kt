package io.github.mangi.eta.agent.translation

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * 屏幕像素级 OCR 引擎：基于 Google ML Kit 离线端侧高精度识别。
 * 遍历全屏文本行，提取高精度 Bounding Box 像素坐标、前景/背景双色与背景像素块。
 */
internal object ScreenOcrEngine {
    /** 中文字形高度约占行高的 0.7，用行高反推原文字号。 */
    private const val TEXT_SIZE_RATIO = 0.7f

    /** 前景簇至少要有这么多样本才认为存在文字，避免把噪点当成笔画色。 */
    private const val MIN_FOREGROUND_SAMPLES = 6

    /** 前景像素占比超过该阈值时估计为粗体（取偏高阈值，避免把常规字重误判为粗体）。 */
    private const val BOLD_FOREGROUND_RATIO = 0.42f

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): List<ScreenTranslationBlock> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    continuation.resume(text)
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }

        val width = bitmap.width
        val height = bitmap.height
        val results = ArrayList<ScreenTranslationBlock>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                // 放宽过滤限制：只要包含有效文字或字母数字字符，即使是单字符（如 "OK", "+", "A"）也完整保留
                if (text.isEmpty() || isPurePunctuation(text)) continue

                val box = line.boundingBox ?: continue
                if (box.width() <= 0 || box.height() <= 0) continue

                // 规范化矩形坐标在屏幕位图范围内
                val safeRect = Rect(
                    box.left.coerceIn(0, width - 1),
                    box.top.coerceIn(0, height - 1),
                    box.right.coerceIn(1, width),
                    box.bottom.coerceIn(1, height),
                )
                if (safeRect.width() <= 3 || safeRect.height() <= 3) continue

                // 智能精确采样背景底色（框外边缘），作为双色分离的锚点
                val sampledColor = sampleBackgroundColor(bitmap, safeRect, width, height)

                // 框内双色分离：背景簇主色 + 前景（笔画）簇主色 + 粗体启发式
                val pixelStats = analyzePixelClusters(bitmap, safeRect, sampledColor)
                val backgroundColor = pixelStats?.backgroundColor ?: sampledColor
                val foregroundColor = pixelStats?.foregroundColor

                // 背景重建底图：裁出该行区域后抹掉原文笔画（按行保留底色渐变）。
                // 必须深拷贝，原 bitmap 在 Controller 里会被 recycle。
                val patch = buildBackgroundPatch(bitmap, safeRect, backgroundColor, foregroundColor)

                results.add(
                    ScreenTranslationBlock(
                        source = text,
                        boundsInScreen = safeRect,
                        sampledBgColor = backgroundColor,
                        foregroundColor = foregroundColor,
                        estimatedTextSizePx = safeRect.height() * TEXT_SIZE_RATIO,
                        backgroundPatch = patch,
                        isBold = pixelStats?.bold == true,
                    ),
                )
            }
        }

        return results
    }

    /**
     * 判断是否是纯标点符号杂讯（如单独的逗号、句号、横杠等）
     */
    private fun isPurePunctuation(text: String): Boolean {
        var hasValidChar = false
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch.code in 0x4E00..0x9FA5 || ch.code in 0x3040..0x30FF || ch.code in 0xAC00..0xD7AF) {
                hasValidChar = true
                break
            }
        }
        return !hasValidChar
    }

    /**
     * 在文字框周围边缘采样背景像素，消除文字本身颜色的干扰，提取真实的底色。
     */
    private fun sampleBackgroundColor(bitmap: Bitmap, rect: Rect, width: Int, height: Int): Int {
        val samplePoints = listOf(
            Pair(rect.left - 2, rect.top - 2),
            Pair(rect.right + 2, rect.top - 2),
            Pair(rect.left - 2, rect.bottom + 2),
            Pair(rect.right + 2, rect.bottom + 2),
            Pair(rect.left - 3, rect.centerY()),
            Pair(rect.right + 3, rect.centerY()),
            Pair(rect.centerX(), rect.top - 3),
            Pair(rect.centerX(), rect.bottom + 3),
            Pair((rect.left + rect.centerX()) / 2, rect.top - 2),
            Pair((rect.left + rect.centerX()) / 2, rect.bottom + 2),
            Pair((rect.right + rect.centerX()) / 2, rect.top - 2),
            Pair((rect.right + rect.centerX()) / 2, rect.bottom + 2),
        )

        val validColors = ArrayList<Int>()
        for ((x, y) in samplePoints) {
            if (x in 0 until width && y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 128) {
                    validColors.add(pixel)
                }
            }
        }

        if (validColors.isEmpty()) {
            return 0xFFF7F8FA.toInt() // 默认明亮背景
        }

        // 使用亮度中位数过滤掉边缘杂散点，提取最真实的背景主色
        val sortedByLuminance = validColors.sortedBy { c ->
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
        val medianColor = sortedByLuminance[sortedByLuminance.size / 2]
        return medianColor
    }

    /** 框内双色分离结果：背景主色、前景笔画色（可能为 null）、粗体估计。 */
    private class PixelClusters(
        val backgroundColor: Int,
        val foregroundColor: Int?,
        val bold: Boolean,
    )

    /**
     * 在文字框内降采样像素，用亮度做 K=2 聚类，把像素分成背景簇与前景（笔画）簇。
     *
     * - 背景簇取平均色，比仅采边缘更能代表渐变/卡片底色；
     * - 前景簇平均色作为译文颜色继承来源；
     * - 前景像素占比用作粗体的保守估计。
     *
     * 采样步长随框大小放大，单行最多约 48×24 个样本，控制单帧开销。
     */
    private fun analyzePixelClusters(bitmap: Bitmap, rect: Rect, edgeSampledColor: Int): PixelClusters? {
        val rectWidth = rect.width()
        val rectHeight = rect.height()
        if (rectWidth <= 0 || rectHeight <= 0) return null
        val stepX = (rectWidth / 48).coerceAtLeast(1)
        val stepY = (rectHeight / 24).coerceAtLeast(1)

        val colors = ArrayList<Int>(64)
        val luminance = ArrayList<Float>(64)
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 128) {
                    colors.add(pixel)
                    luminance.add(luminanceOf(pixel))
                }
                x += stepX
            }
            y += stepY
        }
        if (colors.size < 12) return null

        var lowCenter = luminance.minOrNull() ?: return null
        var highCenter = luminance.maxOrNull() ?: return null
        // 近似纯色（无文字或对比极低）：全部视为背景。
        if (highCenter - lowCenter < 12f) {
            return PixelClusters(edgeSampledColor, null, bold = false)
        }

        // 以亮度极值为初值迭代 2-means。
        repeat(4) {
            var lowSum = 0.0
            var lowCount = 0
            var highSum = 0.0
            var highCount = 0
            for (i in luminance.indices) {
                val value = luminance[i]
                if (abs(value - lowCenter) <= abs(value - highCenter)) {
                    lowSum += value
                    lowCount++
                } else {
                    highSum += value
                    highCount++
                }
            }
            if (lowCount > 0) lowCenter = (lowSum / lowCount).toFloat()
            if (highCount > 0) highCenter = (highSum / highCount).toFloat()
        }

        val edgeLuminance = luminanceOf(edgeSampledColor)
        val lowClusterIsBackground = abs(lowCenter - edgeLuminance) <= abs(highCenter - edgeLuminance)

        var bgR = 0L; var bgG = 0L; var bgB = 0L; var bgCount = 0
        var fgR = 0L; var fgG = 0L; var fgB = 0L; var fgCount = 0
        for (i in colors.indices) {
            val pixel = colors[i]
            val inLowCluster = abs(luminance[i] - lowCenter) <= abs(luminance[i] - highCenter)
            val isBackground = if (lowClusterIsBackground) inLowCluster else !inLowCluster
            if (isBackground) {
                bgR += Color.red(pixel); bgG += Color.green(pixel); bgB += Color.blue(pixel); bgCount++
            } else {
                fgR += Color.red(pixel); fgG += Color.green(pixel); fgB += Color.blue(pixel); fgCount++
            }
        }

        val backgroundColor = if (bgCount > 0) {
            Color.rgb((bgR / bgCount).toInt(), (bgG / bgCount).toInt(), (bgB / bgCount).toInt())
        } else {
            edgeSampledColor
        }
        val foregroundColor = if (fgCount >= MIN_FOREGROUND_SAMPLES) {
            Color.rgb((fgR / fgCount).toInt(), (fgG / fgCount).toInt(), (fgB / fgCount).toInt())
        } else {
            null
        }
        val ratio = fgCount.toFloat() / colors.size
        return PixelClusters(backgroundColor, foregroundColor, bold = ratio >= BOLD_FOREGROUND_RATIO)
    }

    private fun luminanceOf(color: Int): Float =
        0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)

    /** 无前景色时的前景判定：与背景色的欧氏距离平方阈值（约 70/通道）。 */
    private const val FOREGROUND_DISTANCE_SQ = 70 * 70

    /**
     * 生成"背景重建"底图：裁出文字框区域的副本，再把判定为原文笔画的像素抹成该行的底色。
     *
     * 直接裁框会把原文一起带回，贴回去等于没擦；因此先深拷贝该区域，再按行用背景像素均值
     * 回填前景像素——保留纵向渐变（卡片/图片底色），同时去掉字形。整行几乎都是前景时
     * 退回整框背景色。
     */
    private fun buildBackgroundPatch(
        bitmap: Bitmap,
        rect: Rect,
        backgroundColor: Int,
        foregroundColor: Int?,
    ): Bitmap? {
        val patchWidth = rect.width()
        val patchHeight = rect.height()
        if (patchWidth <= 0 || patchHeight <= 0) return null
        return runCatching {
            val cropped = Bitmap.createBitmap(bitmap, rect.left, rect.top, patchWidth, patchHeight)
            val patch = cropped.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val pixels = IntArray(patchWidth * patchHeight)
            patch.getPixels(pixels, 0, patchWidth, 0, 0, patchWidth, patchHeight)

            val minBackgroundPerRow = (patchWidth / 6).coerceAtLeast(1)
            for (row in 0 until patchHeight) {
                val base = row * patchWidth
                var bgR = 0L; var bgG = 0L; var bgB = 0L; var bgCount = 0
                for (col in 0 until patchWidth) {
                    val pixel = pixels[base + col]
                    if (!isForegroundPixel(pixel, backgroundColor, foregroundColor)) {
                        bgR += Color.red(pixel); bgG += Color.green(pixel); bgB += Color.blue(pixel); bgCount++
                    }
                }
                val rowFill = if (bgCount >= minBackgroundPerRow) {
                    Color.rgb((bgR / bgCount).toInt(), (bgG / bgCount).toInt(), (bgB / bgCount).toInt())
                } else {
                    backgroundColor
                }
                for (col in 0 until patchWidth) {
                    val index = base + col
                    if (isForegroundPixel(pixels[index], backgroundColor, foregroundColor)) {
                        // 保留原 alpha，只替换 RGB。
                        pixels[index] = (pixels[index] and 0xFF000000.toInt()) or (rowFill and 0x00FFFFFF)
                    }
                }
            }
            patch.setPixels(pixels, 0, patchWidth, 0, 0, patchWidth, patchHeight)
            patch
        }.getOrNull()
    }

    private fun isForegroundPixel(pixel: Int, backgroundColor: Int, foregroundColor: Int?): Boolean {
        val toBackground = colorDistanceSq(pixel, backgroundColor)
        if (foregroundColor != null) {
            return colorDistanceSq(pixel, foregroundColor) < toBackground
        }
        return toBackground > FOREGROUND_DISTANCE_SQ
    }

    private fun colorDistanceSq(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return dr * dr + dg * dg + db * db
    }
}