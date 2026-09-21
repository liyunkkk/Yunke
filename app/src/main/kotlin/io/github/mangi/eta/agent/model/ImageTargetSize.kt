package io.github.mangi.eta.agent.model

import java.math.BigDecimal

/** Client-side size policy, not a claim about any provider's native resolution tiers. */
internal object ImageTargetSize {
    private val edges = mapOf("1k" to 1024, "1.5k" to 1536, "2k" to 2048, "3k" to 3072, "4k" to 4096)

    fun resolve(ratio: String?, resolution: String?): String {
        if (ratio == null || ratio == "auto" || resolution == null)
            AgentImageGenerationOptions.invalid("像素尺寸协议需要明确比例和分辨率档位，或直接指定 size。")
        val edge = edges[resolution] ?: AgentImageGenerationOptions.invalid("未配置该分辨率档位的像素映射，请指定 size 或 sizes。")
        val parts = ratio.split(':').map(::BigDecimal)
        val w = parts[0]; val h = parts[1]
        fun shortSide(small: BigDecimal, large: BigDecimal): Int = try {
            small.multiply(BigDecimal(edge)).divide(large).intValueExact()
        } catch (_: ArithmeticException) {
            AgentImageGenerationOptions.invalid("该比例无法在长边 $edge 下得到整数像素；请配置精确 sizes 映射，不会换用近似比例。")
        }
        val width = if (w >= h) edge else shortSide(w, h)
        val height = if (h >= w) edge else shortSide(h, w)
        if (minOf(width, height) < 64) AgentImageGenerationOptions.invalid("计算后的短边不足 64 像素，请指定明确 size。")
        return "${width}x$height"
    }
}
