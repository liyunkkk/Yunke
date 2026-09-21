package io.github.mangi.eta.agent.model

import org.json.JSONObject
import kotlin.math.abs

/** Per-call values, never persisted into a model's shared configuration. */
internal data class AgentImageGenerationOptions(
    val aspectRatio: String? = null,
    val resolution: String? = null,
    val size: String? = null,
    val count: Int? = null,
    val quality: String? = null,
    val responseFormat: String? = null,
) {
    val isEmpty get() = this == AgentImageGenerationOptions()

    fun toJson() = JSONObject().also { json ->
        aspectRatio?.let { json.put("aspect_ratio", it) }; resolution?.let { json.put("resolution", it) }
        size?.let { json.put("size", it) }; count?.let { json.put("n", it) }
        quality?.let { json.put("quality", it) }; responseFormat?.let { json.put("response_format", it) }
    }

    /** Apply AFTER model extras. Explicit shape settings replace conflicting configured defaults. */
    fun applyTo(body: JSONObject, model: String) {
        val normalized = fromJson(toJson())
        if (normalized != this) { normalized.applyTo(body, model); return }
        val id = model.lowercase().substringAfterLast('/')
        val grok = id.startsWith("grok-imagine-image")
        val gpt = id.startsWith("gpt-image")
        val dalle = id.startsWith("dall-e-")
        if (grok && size != null) invalid("Grok 生图不支持精确 size；请使用 aspect_ratio 和 resolution，不能保证指定像素尺寸。")
        if (!grok && resolution != null) invalid("当前模型未适配 resolution 档位；请使用该模型支持的 size。")
        if (aspectRatio != null && aspectRatio != "auto" && size == "auto") invalid("指定比例不能与自动尺寸同时使用；请省略 size 或指定一致的像素尺寸。")
        if (aspectRatio != null && size != null && aspectRatio != "auto" && size != "auto") {
            val (w, h) = dimensions(size)
            if (!matchesRatio(w, h, aspectRatio)) invalid("aspect_ratio 与 size 冲突。")
        }
        if (grok) {
            if (aspectRatio != null || resolution != null) body.remove("size")
            aspectRatio?.let { body.put("aspect_ratio", it) }
            resolution?.let { body.put("resolution", it) }
            if (quality != null && id != "grok-imagine-image-2.0") invalid("当前 Grok 型号未适配 quality 参数。")
            if (quality != null && quality !in setOf("auto", "low", "medium")) invalid("Grok quality 仅支持 auto、low、medium。")
        } else {
            if (aspectRatio != null && !gpt && !dalle) invalid("当前模型未适配 aspect_ratio；请明确指定接口支持的 size，不会退回默认方图。")
            var outputSize = size
            if (outputSize == null && aspectRatio != null) {
                outputSize = when {
                    aspectRatio == "auto" && gpt -> "auto"
                    aspectRatio == "1:1" -> "1024x1024"
                    gpt && id.startsWith("gpt-image-2") && aspectRatio == "9:16" -> "864x1536"
                    gpt && id.startsWith("gpt-image-2") && aspectRatio == "16:9" -> "1536x864"
                    gpt && aspectRatio == "2:3" -> "1024x1536"
                    gpt && aspectRatio == "3:2" -> "1536x1024"
                    else -> invalid("该模型无法直接映射此比例；请使用支持该比例的模型或指定其支持的精确 size，不会用近似比例替代。")
                }
            }
            if (outputSize != null) {
                when {
                    id == "dall-e-2" && outputSize !in setOf("256x256", "512x512", "1024x1024") -> invalid("DALL-E 2 不支持此 size。")
                    id == "dall-e-3" && outputSize !in setOf("1024x1024", "1792x1024", "1024x1792") -> invalid("DALL-E 3 不支持此 size。")
                    gpt && !id.startsWith("gpt-image-2") && outputSize !in setOf("auto", "1024x1024", "1024x1536", "1536x1024") -> invalid("当前 GPT Image 型号不支持此 size。")
                    gpt && id.startsWith("gpt-image-2") && outputSize != "auto" -> {
                        val (w, h) = dimensions(outputSize)
                        if (w % 16 != 0 || h % 16 != 0 || w.toDouble() / h !in (1.0 / 3.0)..3.0 ||
                            w.toLong() * h > 3840L * 2160 || maxOf(w, h) > 3840) invalid("此 GPT Image size 超出已适配的 16 像素倍数、比例或分辨率限制。")
                    }
                }
                body.remove("aspect_ratio"); body.remove("resolution")
                body.put("size", outputSize)
            }
            if (gpt && quality != null && quality !in setOf("auto", "low", "medium", "high")) invalid("GPT Image quality 不支持此值。")
            if (dalle && quality != null && quality !in setOf("standard", "hd")) invalid("DALL-E quality 不支持此值。")
            if (gpt && responseFormat != null) invalid("GPT Image 固定返回 base64，不接受 response_format 参数。")
            if (id == "dall-e-3" && count != null && count != 1) invalid("DALL-E 3 单次仅支持一张。")
        }
        count?.let { body.put("n", it) }
        quality?.let { body.put("quality", it) }
        responseFormat?.let { body.put("response_format", it) }
    }

    /** Validate actual decoded dimensions, not a provider's claimed metadata. Never resize or retry. */
    fun dimensionReport(width: Int, height: Int): String {
        val requested = listOfNotNull(aspectRatio?.let { "比例 $it" }, resolution?.let { "分辨率档位 $it" }, size?.let { "尺寸 $it" }).joinToString("，")
        if (width <= 0 || height <= 0) return "IMAGE_DIMENSIONS_UNVERIFIED：无法解码实际宽高" + if (requested.isBlank()) "。" else "；请求 $requested，不能声称符合要求。"
        val problems = mutableListOf<String>()
        if (aspectRatio != null && aspectRatio != "auto" && !matchesRatio(width, height, aspectRatio)) problems += "宽高比不符合 $aspectRatio"
        if (size != null && size != "auto" && dimensions(size) != (width to height)) problems += "像素尺寸不符合 $size"
        // A tier is provider-defined; flag clearly undersized output without claiming an exact edge length.
        if (resolution == "2k" && maxOf(width, height) <= 1024) problems += "实际长边不超过 1024，与请求的 2k 档位明显不符"
        return (if (problems.isEmpty()) "实际尺寸" else "IMAGE_DIMENSIONS_MISMATCH") + "：${width}x$height" +
            (if (requested.isBlank()) "" else "；请求 $requested") +
            (if (problems.isEmpty()) "。" else "；${problems.joinToString("，")}，不能作为符合要求的结果交付。") +
            (if (resolution == null) "" else "分辨率档位已传给接口，不等同于精确像素承诺。")
    }

    companion object {
        val aspectRatios = listOf("auto", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "2:1", "1:2", "19.5:9", "9:19.5", "20:9", "9:20", "21:9", "5:2")
        fun fromJson(json: JSONObject): AgentImageGenerationOptions {
            val keys = setOf("aspect_ratio", "resolution", "size", "n", "quality", "response_format")
            if (json.keys().asSequence().any { it !in keys }) invalid("image_options 含不支持的字段。")
            fun text(key: String): String? {
                if (!json.has(key)) return null
                val value = json.opt(key) as? String ?: invalid("image_options.$key 必须是字符串。")
                if (value.length > 40 || value.isBlank()) invalid("image_options.$key 格式无效。")
                return value.trim().lowercase()
            }
            val ratio = text("aspect_ratio")?.replace('：', ':')?.replace(" ", "")
            if (ratio != null && ratio !in aspectRatios) invalid("不支持此 aspect_ratio。")
            val resolution = text("resolution")
            if (resolution != null && resolution !in setOf("1k", "2k")) invalid("resolution 仅支持已确认的 1k、2k 档位。")
            val size = text("size")?.replace('×', 'x')?.replace(" ", "")
            if (size != null && size != "auto") dimensions(size)
            val count = if (!json.has("n")) null else {
                val number = json.opt("n") as? Number ?: invalid("n 必须是整数。")
                val n = number.toDouble()
                if (n !in 1.0..10.0 || n % 1 != 0.0) invalid("n 必须为 1 至 10 的整数。")
                n.toInt()
            }
            val quality = text("quality")
            if (quality != null && quality !in setOf("auto", "low", "medium", "high", "standard", "hd")) invalid("quality 无效。")
            val format = text("response_format")
            if (format != null && format !in setOf("url", "b64_json")) invalid("response_format 仅支持 url、b64_json。")
            return AgentImageGenerationOptions(ratio, resolution, size, count, quality, format)
        }
        fun dimensions(size: String): Pair<Int, Int> {
            if (!Regex("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}").matches(size)) invalid("size 必须是 WIDTHxHEIGHT，例如 864x1536。")
            return size.split('x').let { it[0].toInt() to it[1].toInt() }
        }
        private fun matchesRatio(width: Int, height: Int, ratio: String): Boolean {
            val parts = ratio.split(':'); val expected = parts[0].toDouble() / parts[1].toDouble()
            return abs(width.toDouble() / height / expected - 1.0) <= 0.01
        }
        fun invalid(message: String): Nothing = throw ImageGenerationParameterException(message)
    }
}

internal class ImageGenerationParameterException(message: String) : IllegalArgumentException(message)
