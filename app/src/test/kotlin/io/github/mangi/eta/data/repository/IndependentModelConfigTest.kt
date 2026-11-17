package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ConversationTitleSettings
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IndependentModelConfigTest {
    private fun base(mandatory: Boolean = false) = AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture",
        assistantId = "yunke-assistant", systemPrompt = "custom assistant",
        thinkingEnabled = true, reasoningEffort = ReasoningEffort.HIGH,
        reasoningCapabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            mandatory = mandatory, canDisable = !mandatory),
        terminalTools = true, browserTools = true, hostedWebSearchEnabled = true,
    )

    @Test fun titleUsesLowestAvailableAndDisablesTools() {
        val title = RuntimeConfigRepository.applyTitleGenerationSettings(base(), ConversationTitleSettings())
        assertEquals(ReasoningEffort.OFF, title.reasoningEffort)
        assertFalse(title.terminalTools)
        assertFalse(title.browserTools)
        assertFalse(title.deviceDirectTools)
        assertFalse(title.deviceSensitiveReadTools)
        assertFalse(title.deviceSensitiveActionTools)
        assertFalse(title.hostedWebSearchEnabled)
        assertEquals(ReasoningEffort.LOW, RuntimeConfigRepository.applyTitleGenerationSettings(
            base(true), ConversationTitleSettings()).reasoningEffort)
        assertEquals(ReasoningEffort.HIGH, RuntimeConfigRepository.applyTitleGenerationSettings(
            base(), ConversationTitleSettings(reasoningEffort = "high")).reasoningEffort)
    }

    @Test fun settingsRoundTripIndependentlyWithoutCredentials() {
        val json = Json { encodeDefaults = true }
        val title = ConversationTitleSettings(enabled = false, modelId = "title-model", reasoningEffort = "low")
        assertEquals(title, json.decodeFromString<ConversationTitleSettings>(json.encodeToString(ConversationTitleSettings.serializer(), title)))
        assertNull(ConversationTitleSettings(reasoningEffort = "future-value").resolvedReasoningEffort)
    }
}
