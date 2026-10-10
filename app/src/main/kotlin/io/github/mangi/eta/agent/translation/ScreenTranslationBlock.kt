package io.github.mangi.eta.agent.translation

import android.graphics.Bitmap
import android.graphics.Rect

data class ScreenTranslationBlock(
    val source: String,
    val boundsInScreen: Rect,
    val sampledBgColor: Int? = null,
    val translated: String? = null,
    /** 原文笔画色（前景簇主色），用于译文颜色继承；null 时按背景亮度在黑白之间自适应。 */
    val foregroundColor: Int? = null,
    /** 由 OCR 行高估算的原文字号（px）；渲染时作为首选字号，放不下才按比例缩小。 */
    val estimatedTextSizePx: Float? = null,
    /** 原文字框的背景像素块（已抹掉原文笔画、保留底色渐变），用于背景重建；null 时回退单色平涂。 */
    val backgroundPatch: Bitmap? = null,
    /** 原文估计为粗体；true 时译文使用 DEFAULT_BOLD，尽量接近原字重。 */
    val isBold: Boolean = false,
)

internal fun String.sameTranslationInputAs(other: String): Boolean {
    if (this == other) return true
    return this.filter { ch -> !ch.isWhitespace() } == other.filter { ch -> !ch.isWhitespace() }
}
