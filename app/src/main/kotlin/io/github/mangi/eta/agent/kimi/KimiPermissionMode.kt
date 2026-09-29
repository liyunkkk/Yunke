package io.github.mangi.eta.agent.kimi

import io.github.mangi.eta.config.Prefs

/**
 * Kimi Code 的授权模式。
 *
 * 取值域来自 Kimi 自身实现：`yolo`（自动批准）/ `auto`（自动，但仍受本地策略约束）/
 * `manual`（每次确认）。服务端建会话与提交提示词都接受 `permission_mode`；不下发时
 * Kimi 侧会退回人工批准，委派便会一直停在等待确认。因此这里默认 `yolo`，
 * 读不到或遇到非法值时一律回落到 `yolo`，避免因为脏配置把委派卡死。
 */
internal object KimiPermissionMode {

    const val YOLO = "yolo"
    const val AUTO = "auto"
    const val MANUAL = "manual"

    /** 面板展示顺序，也是合法取值的白名单。 */
    val all = listOf(YOLO, AUTO, MANUAL)

    /** 解析配置值：空白或非法一律回落 [YOLO]。 */
    fun resolve(value: String?): String {
        val normalized = value?.trim()?.lowercase()
        return normalized?.takeIf { it in all } ?: YOLO
    }

    /** 当前生效模式，读取设置页写入的同一键。 */
    fun current(): String =
        runCatching { resolve(Prefs.getString(Prefs.Keys.KIMI_PERMISSION_MODE, YOLO)) }.getOrDefault(YOLO)
}
