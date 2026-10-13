package io.github.mangi.eta.agent.display

/**
 * 移植自 lejw0925/Eta-vivo: data/model/Settings.kt 中的 VirtualScreenIdleTimeout。
 * Yunke 侧尚未接入 DataStore，这里保留相同的归一化语义：空闲分钟数必须在候选项中，
 * 否则回落为默认值 20。
 */
internal object VirtualScreenIdleTimeout {
    val options = listOf(10, 20, 60, 0)
    fun normalize(minutes: Int): Int = minutes.takeIf { it in options } ?: 20
}
