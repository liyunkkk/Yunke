package io.github.mangi.eta.agent.display

/**
 * 移植自 lejw0925/Eta-vivo: AgentModelClient.ToolStop。
 * Yunke 的 AgentModelClient 没有 ToolStop，这里用独立类承载"暂停本轮任务"的语义，
 * 避免改动 Yunke 现有文件。字段与判定逻辑与 vivo 原版一致。
 */
internal data class VirtualScreenToolStop(val code: String, val message: String) {
    val isVirtualUiPause: Boolean
        get() = code in setOf("UI_NO_PROGRESS", "UI_APP_UNRESPONSIVE", "UI_INPUT_TIMEOUT")
}
