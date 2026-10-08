package io.github.mangi.eta.agent.device

/**
 * 文本匹配的单一来源：主屏 wait_for_text 与副屏 wait_for_text 共用同一套语义。
 *
 * 语义与拆分前的 RootShellDeviceController.matches 完全一致（contains 忽略大小写 / exact /
 * prefix / regex），拆分只为让副屏等待工具不必复制一份判定逻辑。
 */
internal object AgentTextMatcher {
    fun matches(value: String, needle: String, matchMode: String): Boolean =
        when (matchMode.lowercase()) {
            "exact" -> value == needle
            "prefix" -> value.startsWith(needle)
            "regex" -> runCatching { Regex(needle).containsMatchIn(value) }.getOrDefault(false)
            else -> value.contains(needle, ignoreCase = true)
        }
}
