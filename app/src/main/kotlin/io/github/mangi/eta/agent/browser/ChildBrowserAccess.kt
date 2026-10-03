package io.github.mangi.eta.agent.browser

import org.json.JSONObject

/** Per-task child browser grant. Not a user setting. Unknown values are rejected, never upgraded. */
internal enum class ChildBrowserAccess(val wire: String) {
    FULL("full"),
    READ_ONLY("read_only"),
    DISABLED("disabled");

    companion object {
        private val byWire = values().associateBy { it.wire }

        /** Omitted key is full. A present non-string or unknown wire is null so dispatch can refuse. */
        fun fromArgs(args: JSONObject): ChildBrowserAccess? {
            if (!args.has("browser_access")) return FULL
            val raw = args.opt("browser_access")
            return if (raw is String) byWire[raw] else null
        }
    }
}
