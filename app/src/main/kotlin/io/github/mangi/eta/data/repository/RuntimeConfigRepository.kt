package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.agent.model.oauth.GoogleAntigravityOAuth
import io.github.mangi.eta.data.model.SessionGatewayRule
import io.github.mangi.eta.agent.model.oauth.OpenAiCodexOAuth
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun selectedProviderIdFlow() = SettingsDataStore.selectedProviderIdFlow()

    fun selectedModelIdFlow() = SettingsDataStore.selectedModelIdFlow()
    fun selectedTranslationProviderIdFlow() = SettingsDataStore.selectedTranslationProviderIdFlow()
    fun selectedTranslationModelIdFlow() = SettingsDataStore.selectedTranslationModelIdFlow()

    suspend fun selectedProvider(): ProviderSetting? {
        val settings = ProviderRepository.repairSelection()
        return settings.selectedProviderId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelIdIfPresent(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val provider = ProviderRepository.providerByModelId(id)?.takeIf { it.isEnabled } ?: return false
        val model = provider.models.firstOrNull { it.id == id && it.isEnabled } ?: return false
        setSelectedModelId(model.id)
        return true
    }
    suspend fun setSelectedTranslationProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedTranslationProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedTranslationModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedTranslationModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        SettingsDataStore.setTranslationSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
    }
    suspend fun setSelectedTranslationModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        SettingsDataStore.setTranslationSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
    }

    fun conversationTitleSettingsFlow() = SettingsDataStore.conversationTitleSettingsFlow()
    suspend fun setConversationTitleSettings(value: io.github.mangi.eta.data.model.ConversationTitleSettings) = SettingsDataStore.setConversationTitleSettings(value)
    fun assistantOverlaySettingsFlow() = SettingsDataStore.assistantOverlaySettingsFlow()
    suspend fun setAssistantOverlaySettings(value: io.github.mangi.eta.data.model.AssistantOverlaySettings) = SettingsDataStore.setAssistantOverlaySettings(value)

    private suspend fun enabledTextConfig(modelId: String?): AgentModelClient.ModelConfig? {
        if (modelId.isNullOrBlank()) return null
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerByModelId(modelId)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == modelId && it.isEnabled &&
            !it.supportsSpeechSynthesis && !it.supportsImageGeneration && !it.supportsVideoGeneration } ?: return null
        // Use Yunke's OAuth/assistant/custom header path, not Eta's incompatible provider hierarchy.
        return configForProviderAndModel(provider.id, model.id, AssistantRepository.active())
    }

    suspend fun assistantOverlayRuntimeConfig(): AgentModelClient.ModelConfig? {
        val settings = SettingsDataStore.assistantOverlaySettings()
        val base = enabledTextConfig(settings.modelId) ?: currentRuntimeConfig() ?: return null
        return applyAssistantOverlaySettings(base, settings)
    }

    internal fun applyAssistantOverlaySettings(
        base: AgentModelClient.ModelConfig,
        settings: io.github.mangi.eta.data.model.AssistantOverlaySettings,
        defaultThinkingEnabled: Boolean = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
    ): AgentModelClient.ModelConfig {
        val requested = settings.resolvedReasoningEffort
            ?: if (defaultThinkingEnabled) base.effectiveReasoningEffort else ReasoningEffort.OFF
        val effort = base.reasoningCapabilities?.normalize(requested)
            ?: if (requested == ReasoningEffort.OFF) ReasoningEffort.OFF else base.effectiveReasoningEffort
        return base.copy(thinkingEnabled = effort.enablesReasoning, reasoningEffort = effort)
    }

    suspend fun titleGeneratorConfig(conversationModelId: String? = null): AgentModelClient.ModelConfig? {
        val settings = SettingsDataStore.conversationTitleSettings()
        if (!settings.enabled) return null
        val base = enabledTextConfig(settings.modelId) ?: enabledTextConfig(conversationModelId)
            ?: currentRuntimeConfig() ?: return null
        return applyTitleGenerationSettings(base, settings)
    }

    internal fun applyTitleGenerationSettings(
        base: AgentModelClient.ModelConfig,
        settings: io.github.mangi.eta.data.model.ConversationTitleSettings,
    ): AgentModelClient.ModelConfig {
        val capabilities = base.reasoningCapabilities
        val requested = settings.resolvedReasoningEffort
            ?: capabilities?.selectableEfforts?.minByOrNull { it.rank }
            ?: if (capabilities?.mandatory == true) ReasoningEffort.DEFAULT else ReasoningEffort.OFF
        val effort = capabilities?.normalize(requested) ?: ReasoningEffort.OFF
        return base.copy(terminalTools = false, browserTools = false, deviceDirectTools = false,
            deviceSensitiveReadTools = false, deviceSensitiveActionTools = false,
            hostedWebSearchEnabled = false, thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort, summaryOutputLimit = 128,
            errorReconnectPolicy = io.github.mangi.eta.data.model.ErrorReconnectPolicy.NONE.persistedValue)
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val provider = settings.selectedProviderId?.let { ProviderRepository.providerById(it) } ?: return null
        val model = provider.selectedOrFirstModel(settings.selectedModelId) ?: return null
        return buildRuntimeConfig(resolveOAuth(provider), model, AssistantRepository.active()).copy(
            errorReconnectPolicy = settings.errorReconnectPolicy.persistedValue,
        )
    }
    /** 屏幕翻译专用配置：优先取「翻译模型」选择，未指定时回落到默认对话模型。 */
    suspend fun translationRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = SettingsDataStore.settings()
        val translationProviderId = settings.selectedTranslationProviderId
        val baseConfig = if (translationProviderId != null) {
            val provider = ProviderRepository.providerById(translationProviderId)
                ?.takeIf { it.isEnabled }
            if (provider != null) {
                val model = provider.selectedOrFirstModel(settings.selectedTranslationModelId)
                if (model != null) {
                    buildRuntimeConfig(resolveOAuth(provider), model, AssistantRepository.active())
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
        val config = baseConfig ?: currentRuntimeConfig() ?: return null
        return config.copy(
            thinkingEnabled = false,
            reasoningEffort = ReasoningEffort.OFF,
            reasoningCapabilities = null,
        )
    }

    suspend fun syncToRemotePreferences(service: XposedService?): Boolean {
        val prefs = Prefs.remotePreferencesForUi(service) ?: return false
        val config = currentRuntimeConfig() ?: return clearRuntimeConfig(prefs)
        return writeRuntimeConfig(prefs, config)
    }

    suspend fun ensureDefaults(service: XposedService?) {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToRemotePreferences(service)
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(config)

    fun buildRuntimeConfig(
        provider: ProviderSetting,
        model: Model,
        assistant: io.github.mangi.eta.data.model.AssistantProfile? = null,
    ): AgentModelClient.ModelConfig {
        val systemPrompt = assistant?.let {
            io.github.mangi.eta.data.model.AssistantPrompt.build(it.name, it.prompt)
        }?.ifBlank { BuiltinProviders.DEFAULT_SYSTEM_PROMPT }
            ?: BuiltinProviders.DEFAULT_SYSTEM_PROMPT
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val endpointMode = when (provider) {
            is OpenAiCompatibleProviderSetting -> provider.endpointMode
            is CustomProviderSetting -> provider.endpointMode
            is AnthropicProviderSetting -> ""
        }
        val inferOpenAiCatalog = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.CUSTOM &&
            endpointMode == OpenAiEndpointMode.RESPONSES
        val reasoningCapabilities = ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog) {
                io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
        val reasoningEffort = reasoningCapabilities?.normalize(
            model.preferredReasoningEffort ?: ReasoningEffort.OFF,
        ) ?: ReasoningEffort.OFF
        val sessionRule = SessionGatewayRule.decode(provider.sessionGatewayJson)
        return AgentModelClient.ModelConfig(
            assistantId = assistant?.id.orEmpty(),
            providerId = provider.id,
            providerName = provider.name,
            providerType = provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow,
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = endpointMode,
            responsesStripReasoningStatus = provider.responsesStripReasoningStatus,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            thinkingEnabled = reasoningEffort.enablesReasoning,
            reasoningEffort = reasoningEffort,
            reasoningCapabilities = reasoningCapabilities,
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
            sessionModelPattern = sessionRule.modelPattern,
            sessionPathPattern = sessionRule.pathPattern,
            sessionKeyField = sessionRule.safeKeyField(),
            supportsVision = model.supportsVision,
            supportsVideo = model.supportsVideo,
        )
    }

    private fun writeRuntimeConfig(
        prefs: SharedPreferences,
        config: AgentModelClient.ModelConfig,
    ): Boolean =
        runCatching {
            prefs.edit()
                .putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
                .commit()
        }.getOrDefault(false)

    private fun clearRuntimeConfig(prefs: SharedPreferences): Boolean =
        runCatching {
            prefs.edit()
                .remove(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
                .commit()
        }.getOrDefault(false)

    internal suspend fun configForProviderAndModel(
        providerId: String,
        modelId: String,
        assistant: io.github.mangi.eta.data.model.AssistantProfile? = null,
    ): AgentModelClient.ModelConfig? {
        val provider = ProviderRepository.providerById(providerId)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == modelId && it.isEnabled } ?: return null
        return buildRuntimeConfig(resolveOAuth(provider), model, assistant).copy(
            errorReconnectPolicy = SettingsDataStore.settings().errorReconnectPolicy.persistedValue,
        )
    }

    private suspend fun resolveOAuth(provider: ProviderSetting): ProviderSetting {
        val context = ProviderRepository.context()
        return if (GoogleAntigravityOAuth.usesBackend(provider)) {
            val resolved = GoogleAntigravityOAuth.withResolvedAuth(context, provider)
            GoogleAntigravityOAuth.ensureProjectId(context, resolved.id, resolved.apiKey)
            resolved
        } else {
            OpenAiCodexOAuth.withResolvedAuth(context, provider)
        }
    }
}
