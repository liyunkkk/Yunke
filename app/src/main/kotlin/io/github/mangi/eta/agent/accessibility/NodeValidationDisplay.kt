package io.github.mangi.eta.agent.accessibility

/**
 * 节点动作校验用哪个 display 的根。
 *
 * 默认屏（0）沿用 rootInActiveWindow；副屏等其它 display 必须用该 display 自己的
 * 聚焦／活跃应用窗口根，否则副屏快照的 windowId 永远对不上默认屏的活动窗口，
 * replace_text / clear_text 会被误判成 STALE_WINDOW。
 *
 * 取不到该 display 的根时调用方一律返回 STALE_WINDOW，绝不回退主屏。
 */
internal object NodeValidationDisplay {
    fun usesDisplayScopedRoot(displayId: Int): Boolean =
        displayId != android.view.Display.DEFAULT_DISPLAY
}
