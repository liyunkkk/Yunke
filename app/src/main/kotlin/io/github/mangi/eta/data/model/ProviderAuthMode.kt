package io.github.mangi.eta.data.model

internal object ProviderAuthMode {
    const val API_KEY = "api_key"
    const val OAUTH = "oauth"
    const val OAUTH_ANTIGRAVITY = "oauth_antigravity"

    const val DEFAULT = API_KEY

    fun parse(value: String?): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            OAUTH -> OAUTH
            OAUTH_ANTIGRAVITY -> OAUTH_ANTIGRAVITY
            else -> API_KEY
        }
    }

    fun isOAuth(value: String?): Boolean = parse(value) != API_KEY

    fun isAntigravity(value: String?): Boolean = parse(value) == OAUTH_ANTIGRAVITY
}

internal val ProviderSetting.usesOAuth: Boolean
    get() = ProviderAuthMode.isOAuth(authMode)
