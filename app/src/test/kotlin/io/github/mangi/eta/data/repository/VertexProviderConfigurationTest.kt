package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ProviderWithModels
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.ui.pages.providers.ProviderConfigDraft
import io.github.mangi.eta.ui.pages.providers.buildUpdatedProvider
import io.github.mangi.eta.ui.pages.providers.withGeminiEndpointMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexProviderConfigurationTest {
    private val model = Model(id = "neon", modelId = "gemini-4-neon", displayName = "My Neon")
    private val provider = GeminiProviderSetting(
        id = "vertex", name = "Vertex AI", baseUrl = GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL,
        apiKey = "fixture", endpointMode = GeminiEndpointMode.VERTEX_AI,
        vertexProject = "my-project", vertexLocation = "global", models = listOf(model),
        customHeaders = listOf(CustomHeader("x-fixture", "kept")), sessionGatewayJson = "{}",
    )

    @Test
    fun nativeTypeAndVertexFieldsRoundTripEntityAndSerialization() {
        val entity = provider.toEntity()
        assertEquals(ProviderTypes.GEMINI, entity.type)
        assertEquals(GeminiEndpointMode.VERTEX_AI, entity.endpointMode)
        assertEquals("my-project", entity.vertexProject)
        assertEquals("global", entity.vertexLocation)
        val restored = ProviderWithModels(entity, provider.toModelEntities()).toDomain() as GeminiProviderSetting
        assertEquals(provider, restored)
        val encoded = Json.encodeToString<ProviderSetting>(provider)
        assertEquals(provider, Json.decodeFromString<ProviderSetting>(encoded))
    }

    @Test
    fun runtimePreservesVertexRoutingAndUsesOnlyDocumentedReasoning() {
        val config = RuntimeConfigRepository.buildRuntimeConfig(provider.copy(hostedWebSearchEnabled = true), model)
        assertEquals(ProviderTypes.GEMINI, config.providerType)
        assertEquals("", config.openAiEndpointMode)
        assertEquals(GeminiEndpointMode.VERTEX_AI, config.geminiEndpointMode)
        assertEquals("my-project", config.vertexProject)
        assertEquals("global", config.vertexLocation)
        assertFalse(config.usesHostedWebSearch)
        assertEquals(GeminiModelCapabilities.resolveVertexReasoning(model), config.reasoningCapabilities)
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
    }

    @Test
    fun editorPreservesCustomBaseAndStoresProjectLocationAndYunkeFields() {
        val source = provider.copy(baseUrl = "https://proxy.example/v1beta1")
        val draft = ProviderConfigDraft.from(source).copy(vertexProject = " new-project ", vertexLocation = " EU ")
        val saved = buildUpdatedProvider(
            source = source, name = draft.name, baseUrl = draft.baseUrl, apiKey = draft.apiKey,
            isEnabled = draft.isEnabled, endpointMode = draft.endpointMode,
            hostedWebSearchEnabled = true, anthropicVersion = draft.anthropicVersion,
            vertexProject = draft.vertexProject, vertexLocation = draft.vertexLocation,
            customHeaders = draft.headers.map { it.header }, customBody = source.customBody,
            balanceOption = draft.balanceOption, sessionGatewayJson = source.sessionGatewayJson,
        ) as GeminiProviderSetting
        assertEquals("new-project", saved.vertexProject)
        assertEquals("eu", saved.vertexLocation)
        assertEquals(source.customHeaders, saved.customHeaders)
        assertEquals(source.sessionGatewayJson, saved.sessionGatewayJson)
        assertEquals(source.balanceOption, saved.balanceOption)
        assertFalse(saved.hostedWebSearchEnabled)
        assertEquals(source.baseUrl, draft.withGeminiEndpointMode(GeminiEndpointMode.AI_STUDIO).baseUrl)
    }

    @Test
    fun switchingPlatformReplacesOnlyOfficialDefaults() {
        val studio = ProviderConfigDraft.from(provider.copy(
            endpointMode = GeminiEndpointMode.AI_STUDIO, baseUrl = GeminiProviderSetting.DEFAULT_BASE_URL,
        ))
        val vertex = studio.withGeminiEndpointMode(GeminiEndpointMode.VERTEX_AI)
        assertEquals(GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL, vertex.baseUrl)
        assertEquals(GeminiProviderSetting.DEFAULT_BASE_URL, vertex.withGeminiEndpointMode(GeminiEndpointMode.AI_STUDIO).baseUrl)
        assertEquals("my-project", vertex.vertexProject)
        assertTrue((provider.copy(endpointMode = "unknown")).isVertexAi.not())
    }
}
