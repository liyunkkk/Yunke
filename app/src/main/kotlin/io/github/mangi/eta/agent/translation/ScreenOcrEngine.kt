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

/**
 * 屏幕像素级 OCR 引擎：基于 Google ML Kit 离线端侧高精度识别。
 * 遍历全屏文本行，提取高精度 Bounding Box 像素坐标与采样背景色。
 */
internal object ScreenOcrEngine {
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

                // 智能精确采样背景底色
                val sampledColor = sampleBackgroundColor(bitmap, safeRect, width, height)

                results.add(
                    ScreenTranslationBlock(
                        source = text,
                        boundsInScreen = safeRect,
                        sampledBgColor = sampledColor,
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
}