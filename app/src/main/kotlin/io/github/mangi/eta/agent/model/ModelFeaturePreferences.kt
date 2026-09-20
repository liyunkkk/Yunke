package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ProviderRepository

internal enum class ModelFeature(val key: String) { VISION("agent_auxiliary_vision"), TITLE("agent_title_model") }

/** Store references only; credentials remain in the provider repository. */
internal data class ModelFeatureSelection(val custom: Boolean, val providerId: String, val modelId: String) {
    suspend fun resolve(): AgentModelClient.ModelConfig? {
        if (providerId.isBlank() || modelId.isBlank()) return null
        val provider = ProviderRepository.providerById(providerId)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == modelId && it.isEnabled } ?: return null
        if (model.supportsSpeechSynthesis || model.supportsImageGeneration || model.supportsVideoGeneration) return null
        return RuntimeConfigRepository.configForProviderAndModel(providerId, modelId, assistant = null)
    }
}

internal object ModelFeaturePreferences {
    fun selection(feature: ModelFeature) = ModelFeatureSelection(
        Prefs.getString("${feature.key}_custom") == "true",
        Prefs.getString("${feature.key}_provider"),
        Prefs.getString("${feature.key}_model"),
    )
    fun save(feature: ModelFeature, selection: ModelFeatureSelection) {
        Prefs.putString("${feature.key}_provider", selection.providerId)
        Prefs.putString("${feature.key}_model", selection.modelId)
        Prefs.putString("${feature.key}_custom", selection.custom.toString())
    }
    fun visionEnabled() = selection(ModelFeature.VISION).custom
}
