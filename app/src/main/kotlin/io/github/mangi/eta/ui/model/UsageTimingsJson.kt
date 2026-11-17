package io.github.mangi.eta.ui.model

import org.json.JSONObject

/**
 * 单轮耗时字段的紧凑持久化格式（存 Room 的 `usage_timings_json`）。
 *
 * 只存展示用耗时：token 用量已有各自的列，不在这里重复存。
 * - 编码：`{"s":3,"l":8400,"t":2100,"f":1200,"n":3,"d":6000,"k":120}`
 * - 没有任何耗时 → 空串，不写 `{}`，旧行/新空行读回都是 null。
 * - 解码对类型宽松（JSON 往返会把小 long 变 Int、把数字变 Double/String），
 *   任何损坏或非法内容一律读回 null，绝不抛给调用方。
 * - 毫秒用 Long 原样存，5,000,000,000 这种大值不丢精度。
 */
internal object UsageTimingsJson {
    private const val KEY_STEP_COUNT = "s"
    private const val KEY_LLM_MS = "l"
    private const val KEY_TOOL_MS = "t"
    private const val KEY_TTFT_MS = "f"
    private const val KEY_TTFT_STEPS = "n"
    private const val KEY_DECODE_MS = "d"
    private const val KEY_DECODE_TOKENS = "k"

    /** 无耗时或全空时返回空串（列默认值），避免写一堆无意义的 `{}`。 */
    fun encode(usage: TokenUsageUi?): String {
        if (usage == null || !usage.hasTimingFields()) return ""
        val json = JSONObject()
        usage.stepCount?.let { json.put(KEY_STEP_COUNT, it) }
        usage.llmMs?.let { json.put(KEY_LLM_MS, it) }
        usage.toolMs?.let { json.put(KEY_TOOL_MS, it) }
        usage.ttftMs?.let { json.put(KEY_TTFT_MS, it) }
        usage.ttftSteps?.let { json.put(KEY_TTFT_STEPS, it) }
        usage.decodeMs?.let { json.put(KEY_DECODE_MS, it) }
        usage.decodeTokens?.let { json.put(KEY_DECODE_TOKENS, it) }
        return json.toString()
    }

    fun decode(raw: String?): TokenUsageUi? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "{}" || text == "null") return null
        return runCatching {
            val json = JSONObject(text)
            TokenUsageUi(
                stepCount = json.intOrNull(KEY_STEP_COUNT),
                llmMs = json.longOrNull(KEY_LLM_MS),
                toolMs = json.longOrNull(KEY_TOOL_MS),
                ttftMs = json.longOrNull(KEY_TTFT_MS),
                ttftSteps = json.intOrNull(KEY_TTFT_STEPS),
                decodeMs = json.longOrNull(KEY_DECODE_MS),
                decodeTokens = json.intOrNull(KEY_DECODE_TOKENS),
            ).takeIf { it.hasTimingFields() }
        }.getOrNull()
    }

    private fun JSONObject.intOrNull(key: String): Int? = when (val value = opt(key)) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun JSONObject.longOrNull(key: String): Long? = when (val value = opt(key)) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

/** 是否至少带了一项展示用耗时（token 字段不算）。 */
internal fun TokenUsageUi.hasTimingFields(): Boolean =
    stepCount != null || llmMs != null || toolMs != null ||
        ttftMs != null || ttftSteps != null || decodeMs != null || decodeTokens != null
