package io.github.mangi.eta.hook.vivo

import io.github.mangi.eta.agent.vivo.VivoBridgeDiagnostics.Reason

/** Conservative manual-text eligibility; other native capabilities are never consumed. */
internal object VivoNativePolicy {
    data class Shape(
        val agentId: String?, val inputType: Int, val bizSource: String?,
        val renderText: Boolean, val shortcut: Boolean, val regenerate: Boolean,
        val skipRemote: Boolean, val recommended: Boolean, val specialized: Boolean,
    )
    fun eligible(s: Shape) = rejection(s) == null
    fun rejection(s: Shape): Reason? = when {
        s.agentId != "little_v" -> Reason.AGENT_ID
        s.inputType != 0 -> Reason.INPUT_TYPE
        // Plain BottomInput is observed manual text, not a vendor BizSource constant.
        !s.bizSource.isNullOrEmpty() && s.bizSource != "BottomInput" -> Reason.BIZ_SOURCE
        !s.renderText -> Reason.RENDER_TEXT
        s.shortcut -> Reason.SHORTCUT
        s.regenerate -> Reason.REGENERATE
        s.skipRemote -> Reason.SKIP_REMOTE
        s.recommended -> Reason.RECOMMENDED
        s.specialized -> Reason.SPECIALIZED
        else -> null
    }
    fun prompt(text: String?, requirePrefix: Boolean): String? {
        var value = text?.trim() ?: return null
        if (requirePrefix) {
            val prefix = Regex("(?i)^/?agent(?:\\s+|[:：]\\s*)").find(value) ?: return null
            value = value.substring(prefix.range.last + 1).trim()
        }
        return value.takeIf { it.isNotBlank() && it.length <= 4000 && '\u0000' !in it }
    }
    fun validId(id: String) = Regex("[A-Za-z0-9_.:-]{1,160}").matches(id)
}
