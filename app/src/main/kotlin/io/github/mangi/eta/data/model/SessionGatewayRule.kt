package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class SessionGatewayRule(
    val modelPattern: String = DEFAULT_MODEL,
    val pathPattern: String = DEFAULT_PATH,
    val retention: String = DEFAULT_RETENTION,
    val keySource: String = DEFAULT_SOURCE,
    val keyField: String = DEFAULT_FIELD,
) {
    fun matches(model: String, url: String): Boolean {
        val modelOk = runCatching { Regex(modelPattern).matches(model) }.getOrDefault(false)
        val pathOk = runCatching { Regex(pathPattern).containsMatchIn(url) }.getOrDefault(false)
        return modelOk && pathOk
    }

    fun safeKeyField(): String =
        keyField.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) } ?: DEFAULT_FIELD

    companion object {
        const val DEFAULT_MODEL = "^gpt-.*$"
        const val DEFAULT_PATH = "/v1/responses"
        const val DEFAULT_RETENTION = "继承全局默认"
        const val DEFAULT_SOURCE = "gjson"
        const val DEFAULT_FIELD = "prompt_cache_key"

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String): SessionGatewayRule {
            if (raw.isBlank()) return SessionGatewayRule()
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(SessionGatewayRule())
        }

        fun encode(rule: SessionGatewayRule): String = json.encodeToString(serializer(), rule)
    }
}
