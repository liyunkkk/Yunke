package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.model.RequestOverheadCalibration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * In-memory only: each generation owns a complete provider/model value snapshot and a
 * bounded LRU (including unavailable/null signatures). IDs select within that snapshot;
 * they are NOT the configuration identity. No configuration, key or digest is logged.
 *
 * IO/Main readers single-flight on their captured generation. Replacing configuration
 * does not wait for an old digest, and a late reader retries against the current generation
 * instead of publishing an old result into it. This cache never owns usage-run validity.
 */
internal class RouteSignatureCache(
    private val capacity: Int = 64,
    private val computeStrict: (ProviderSetting, Model) -> String? = { provider, model ->
        RequestOverheadCalibration.routeSignature(provider, model).takeIf { it.isNotBlank() }
    },
    private val onMiss: () -> Unit = {},
) {
    init { require(capacity > 0) }

    private enum class Kind { STRICT, CONTEXT }
    private data class Key(val providerId: String, val modelId: String, val kind: Kind)
    private class Value(val signature: String?)
    private class Configuration(val providers: List<ProviderSetting>) {
        // JsonObject equality ignores member order, but the existing signature algorithm
        // uses value.toString(). Preserve that exact configuration distinction as well.
        val bodyValues = providers.flatMap { provider ->
            provider.customBody.map { it.value.toString() } +
                provider.models.flatMap { model -> model.customBody.map { it.value.toString() } }
        }
        val entries = LinkedHashMap<Key, Value>(16, 0.75f, true)
    }

    @Volatile private var configuration = Configuration(emptyList())

    /** Replace BEFORE the new selection is visible, including deletion/disable/revert. */
    @Synchronized fun updateProviders(providers: List<ProviderSetting>): Boolean {
        val snapshot = Configuration(providers.map { it.signatureSnapshot() })
        if (configuration.providers == snapshot.providers && configuration.bodyValues == snapshot.bodyValues) return false
        configuration = snapshot
        return true
    }

    fun strict(providerId: String, modelId: String): String? = readCurrent { snapshot ->
        cached(snapshot, Key(providerId, modelId, Kind.STRICT), computeStrict)
    }

    /** The fallback is the caller's unchanged actual-local-v1 algorithm. */
    fun context(
        providerId: String,
        modelId: String,
        computeActual: (ProviderSetting, Model) -> String,
    ): String? = readCurrent { snapshot ->
        cached(snapshot, Key(providerId, modelId, Kind.CONTEXT)) { provider, model ->
            cached(snapshot, Key(providerId, modelId, Kind.STRICT), computeStrict)
                ?: computeActual(provider, model)
        }
    }

    private fun readCurrent(read: (Configuration) -> String?): String? {
        while (true) {
            val snapshot = configuration
            val value = synchronized(snapshot) { read(snapshot) }
            if (configuration === snapshot) return value
        }
    }

    // Called only under this generation's lock, also for the nested strict lookup.
    private fun cached(
        snapshot: Configuration,
        key: Key,
        compute: (ProviderSetting, Model) -> String?,
    ): String? {
        snapshot.entries[key]?.let { return it.signature }
        onMiss()
        val provider = snapshot.providers.firstOrNull { it.id == key.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == key.modelId && it.isEnabled }
        val result = if (provider == null || model == null) null else compute(provider, model)
        snapshot.entries[key] = Value(result)
        while (snapshot.entries.size > capacity) {
            val eldest = snapshot.entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return result
    }

    private fun ProviderSetting.signatureSnapshot(): ProviderSetting {
        val models = models.map { it.signatureSnapshot() }
        val headers = customHeaders.toList()
        val body = customBody.map { it.signatureSnapshot() }
        return when (this) {
            is OpenAiCompatibleProviderSetting -> copy(models = models, customHeaders = headers, customBody = body)
            is CustomProviderSetting -> copy(models = models, customHeaders = headers, customBody = body)
            is AnthropicProviderSetting -> copy(models = models, customHeaders = headers, customBody = body)
        }
    }

    private fun Model.signatureSnapshot(): Model = copy(
        inputModalities = inputModalities.toList(), outputModalities = outputModalities.toList(),
        customHeaders = customHeaders.toList(), customBody = customBody.map { it.signatureSnapshot() },
        reasoningCapabilities = reasoningCapabilities?.let { it.copy(supportedEfforts = it.supportedEfforts.toList()) },
        reasoningCapabilitiesOverride = reasoningCapabilitiesOverride?.let {
            it.copy(supportedEfforts = it.supportedEfforts.toList())
        },
    )

    private fun CustomBody.signatureSnapshot(): CustomBody = copy(value = value.signatureSnapshot())

    private fun JsonElement.signatureSnapshot(): JsonElement = when (this) {
        is JsonObject -> JsonObject(mapValues { (_, value) -> value.signatureSnapshot() })
        is JsonArray -> JsonArray(map { it.signatureSnapshot() })
        else -> this // JsonPrimitive/JsonNull have immutable values.
    }
}
