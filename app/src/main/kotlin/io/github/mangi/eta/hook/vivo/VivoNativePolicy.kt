package io.github.mangi.eta.hook.vivo

/** Conservative manual-text eligibility; other native capabilities are never consumed. */
internal object VivoNativePolicy {
    data class Shape(
        val agentId: String?, val inputType: Int, val bizSource: String?,
        val renderText: Boolean, val shortcut: Boolean, val regenerate: Boolean,
        val skipRemote: Boolean, val recommended: Boolean, val specialized: Boolean,
    )
    fun eligible(s: Shape) = s.agentId == "little_v" && s.inputType == 0 &&
        s.bizSource.isNullOrEmpty() && s.renderText && !s.shortcut && !s.regenerate &&
        !s.skipRemote && !s.recommended && !s.specialized
    fun prompt(text: String?, requirePrefix: Boolean): String? {
        var value = text?.trim() ?: return null
        if (requirePrefix) {
            val prefix = Regex("(?i)^agent(?:\\s+|[:：]\\s*)").find(value) ?: return null
            value = value.substring(prefix.range.last + 1).trim()
        }
        return value.takeIf { it.isNotBlank() && it.length <= 4000 && '\u0000' !in it }
    }
    fun validId(id: String) = Regex("[A-Za-z0-9_.:-]{1,160}").matches(id)
}
