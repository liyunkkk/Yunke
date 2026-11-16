package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.AssistantOverlaySettings
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

    @Test fun overlayExplicitEffortIsIndependentFromGlobalThinking() {
        val original = base()
        val overlay = RuntimeConfigRepository.applyAssistantOverlaySettings(original,
            AssistantOverlaySettings(reasoningEffort = "high"), defaultThinkingEnabled = false)
        assertEquals(ReasoningEffort.HIGH, overlay.reasoningEffort)
        assertTrue(overlay.thinkingEnabled)
        assertEquals(original.assistantId, overlay.assistantId)
        assertEquals(original.systemPrompt, overlay.systemPrompt)
        assertEquals(original.customHeaders, overlay.customHeaders)
        assertEquals(original, base())
    }

    @Test fun overlayAutomaticFollowsGlobalSwitchAndNormalizesExplicitEffort() {
        val off = RuntimeConfigRepository.applyAssistantOverlaySettings(base(),
            AssistantOverlaySettings(), defaultThinkingEnabled = false)
        assertEquals(ReasoningEffort.OFF, off.reasoningEffort)
        assertFalse(off.thinkingEnabled)
        val normalized = RuntimeConfigRepository.applyAssistantOverlaySettings(base(),
            AssistantOverlaySettings(reasoningEffort = "max"), defaultThinkingEnabled = true)
        assertEquals(ReasoningEffort.HIGH, normalized.reasoningEffort)
        val required = RuntimeConfigRepository.applyAssistantOverlaySettings(base(true),
            AssistantOverlaySettings(reasoningEffort = "off"), defaultThinkingEnabled = false)
        assertEquals(ReasoningEffort.LOW, required.reasoningEffort)
    }

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
        val overlay = AssistantOverlaySettings(modelId = "overlay-model", reasoningEffort = "high")
        assertEquals(title, json.decodeFromString<ConversationTitleSettings>(json.encodeToString(ConversationTitleSettings.serializer(), title)))
        assertEquals(overlay, json.decodeFromString<AssistantOverlaySettings>(json.encodeToString(AssistantOverlaySettings.serializer(), overlay)))
        assertNull(ConversationTitleSettings(reasoningEffort = "future-value").resolvedReasoningEffort)
        assertNull(AssistantOverlaySettings(reasoningEffort = "future-value").resolvedReasoningEffort)
    }
}
