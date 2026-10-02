package io.github.mangi.eta.agent.vivo

/** Experimental, single-device protocol. Never authorizes AgentRuntimeService. */
internal object VivoTextBridgePolicy {
    const val PACKAGE = "com.vivo.ai.copilot"
    // PackageManager SigningInfo.apkContentsSigners, not signingCertificateHistory or APK-tool output.
    const val SIGNER = "915191fccf5058fa4b21c9c8ea8897040d313d18838850e986fc00055117d1db"
    const val REQUEST = 1
    const val CANCEL = 2
    const val RESULT = 3
    const val REJECTED = 4
    const val MAX_PROMPT = 4000
    const val MAX_RESULT = 16000
    const val TIMEOUT_MS = 90_000L
    private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")

    data class Command(val id: String, val prompt: String?)

    fun deviceAllowed(enabled: Boolean, model: String, sdk: Int) =
        enabled && model == "V2419A" && sdk == 35

    fun callerAllowed(uid: Int, packages: List<String>, version: Long, signers: List<String>) =
        uid in 10000..19999 && packages == listOf(PACKAGE) &&
            version == 6090021L && signers == listOf(SIGNER)

    fun command(what: Int, values: Map<String, Any?>): Command? {
        val keys = when (what) {
            REQUEST -> setOf("request_id", "prompt")
            CANCEL -> setOf("request_id")
            else -> return null
        }
        if (values.keys != keys) return null
        val id = values["request_id"] as? String ?: return null
        if (!idPattern.matches(id)) return null
        if (what == CANCEL) return Command(id, null)
        val prompt = values["prompt"] as? String ?: return null
        if (prompt.isBlank() || prompt.length > MAX_PROMPT || '\u0000' in prompt) return null
        return Command(id, prompt)
    }
}

/** Process-lifetime replay fence: never evict IDs and accidentally charge for a replay. */
internal class VivoTextBridgeLedger(private val limit: Int = 256) {
    enum class Admission { ACCEPTED, DUPLICATE, BUSY, FULL }
    private val seen = HashSet<Pair<Int, String>>()
    private var active: Pair<Int, String>? = null

    @Synchronized fun admit(uid: Int, id: String): Admission {
        val key = uid to id
        if (key in seen) return Admission.DUPLICATE
        if (active != null) return Admission.BUSY
        if (seen.size >= limit) return Admission.FULL
        seen.add(key)
        active = key
        return Admission.ACCEPTED
    }

    @Synchronized fun release(uid: Int, id: String) {
        if (active == uid to id) active = null
    }
}
