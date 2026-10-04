package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs

/**
 * 模型请求重试的可配置项。
 *
 * 默认与上游重连引擎保持一致：最多重试 3 次（2s/4s/8s）。退避抖动默认关闭，
 * 可在设置里开启，让同一时间的多路请求不会精确对齐在同一时刻重发。次数只允许 0/3/5/8，
 * 其它脏值一律回落默认 3。
 */
internal object ModelRetrySettings {

    const val DEFAULT_COUNT = 3

    /** 设置页可选项，也是合法值白名单；0 表示不重试。 */
    val allowedCounts = listOf(0, 3, 5, 8)

    /** 解析配置值：非法或缺失一律回落 [DEFAULT_COUNT]。 */
    fun parseCount(raw: String?): Int =
        raw?.trim()?.toIntOrNull()?.takeIf { it in allowedCounts } ?: DEFAULT_COUNT

    fun configuredCount(): Int =
        runCatching { parseCount(Prefs.getString(Prefs.Keys.MODEL_RETRY_COUNT, DEFAULT_COUNT.toString())) }
            .getOrDefault(DEFAULT_COUNT)

    fun jitterEnabled(): Boolean =
        runCatching { Prefs.isEnabled(Prefs.Keys.MODEL_RETRY_JITTER) }.getOrDefault(false)
}
