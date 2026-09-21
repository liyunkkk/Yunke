package io.github.mangi.eta.agent.kimi

import java.io.File

/**
 * Kimi Code 容器内配置（`<KIMI_CODE_HOME>/config.toml`）的轻量读取。
 *
 * 只解析 App 需要的 `default_model` 一行，不引入 TOML 依赖：该值决定
 * `kimi web` 在会话未显式指定模型时使用的别名，也是 `delegate_to_kimi_code`
 * 委派时必须随提示词下发的模型。
 *
 * 现场故障（`model.not_configured`）的根因就是这里：容器内 `config.toml`
 * 已配好 `default_model`，但 App 建会话与发提示词时从不携带模型，
 * 服务端只把 `default_model` 当作"未指定时的回落"，REST 会话的 profile
 * 仍是空串，于是每一轮都在 `turn.ended` 里以 failed 结束。
 */
internal object KimiCodeConfig {

    /** 相对 rootfs 的配置路径；`KIMI_CODE_HOME` 在容器内固定为 `/root/.kimi-code`。 */
    const val RELATIVE_PATH = "root/.kimi-code/config.toml"

    /**
     * 顶层 `default_model` 键。
     *
     * 段落内不会出现同名键（`[models.*]` 用的是 `model`），因此无需完整解析
     * TOML 即可安全提取；值两侧的引号按 TOML 基本字符串处理。
     */
    private val DEFAULT_MODEL_REGEX =
        Regex("(?m)^\\s*default_model\\s*=\\s*[\"']([^\"']+)[\"']\\s*$")

    /** 从配置文本中取出 `default_model`；缺失或为空时返回 null。 */
    fun parseDefaultModel(text: String?): String? =
        text?.let { DEFAULT_MODEL_REGEX.find(it)?.groupValues?.get(1) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** 直接读取 rootfs 内的配置文件；App 进程无权限或文件缺失时返回 null。 */
    fun read(rootfs: File): String? = runCatching {
        File(rootfs, RELATIVE_PATH).readText(Charsets.UTF_8)
    }.getOrNull()
}
