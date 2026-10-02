package io.github.mangi.eta.config

import io.github.mangi.eta.ui.model.RequestOverheadCalibration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Local provider/model preferences, deliberately not part of a conversation checkpoint. */
internal object RequestOverheadCalibrationStore {
    private val json = Json { ignoreUnknownKeys = true }

    // Length prefixes make the pair unambiguous even when identifiers contain delimiters.
    private fun key(providerId: String, modelId: String): String =
        "agent_request_overhead_calibration_v1:${providerId.length}:$providerId:${modelId.length}:$modelId"

    fun read(providerId: String, modelId: String): RequestOverheadCalibration.Sample? = runCatching {
        val raw = Prefs.getString(key(providerId, modelId)).ifEmpty { return null }
        json.decodeFromString<RequestOverheadCalibration.Sample>(raw)
            .takeIf { it.offsetTokens >= 0 && it.samples > 0 && it.measuredOverheadTokens >= 0 }
    }.getOrNull()

    fun save(providerId: String, modelId: String, sample: RequestOverheadCalibration.Sample) {
        Prefs.putString(key(providerId, modelId), json.encodeToString(sample))
    }
}
