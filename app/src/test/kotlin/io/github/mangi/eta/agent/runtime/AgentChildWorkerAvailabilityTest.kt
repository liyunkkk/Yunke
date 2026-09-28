package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChildWorkerAvailabilityTest {
    private val profile = SubAgentProfile("stable-worker", "Worker", role = "review",
        providerId = "provider", modelId = "explicit-selection")
    private val provider = OpenAiCompatibleProviderSetting("provider", "Provider", "https://example.invalid/v1",
        apiKey = "must-not-leak", models = listOf(Model("explicit-selection", "explicit-api-model", "Selected")))
    private val model = RuntimeConfigRepository.buildRuntimeConfig(provider, provider.models.single())
    private val available = ChildTaskConfigPolicy.Candidate(
        ChildTaskConfigPolicy.WorkerKey("owner", profile.id, profile.role),
        ChildTaskConfigPolicy.Availability.AVAILABLE,
        "private-revision-token",
        ChildWorkerConfigResolver.Configuration(profile, model),
    )
    private val unavailable = ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>(
        ChildTaskConfigPolicy.WorkerKey("owner", "missing-worker", "research"),
        ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE,
    )

    @Test fun coordinatorUsesOnlyAvailableExactSelectionInOriginalOrder() {
        val configured = AgentChildWorkerAvailability.configuredChildren(listOf(unavailable, available))
        assertEquals(1, configured.size)
        assertSame(profile, configured.single().first)
        assertSame(model, configured.single().second)
        assertEquals("explicit-api-model", configured.single().second.model)
    }

    @Test fun safePromptRetainsUnavailableReasonButNotCredentialsOrRevision() {
        val prompt = AgentChildWorkerAvailability.appendToPrompt("question", listOf(unavailable, available))
        assertTrue(prompt.startsWith("question"))
        assertTrue(prompt.contains("missing-worker"))
        assertTrue(prompt.contains("MODEL_UNAVAILABLE"))
        assertTrue(prompt.contains("stable-worker"))
        assertFalse(prompt.contains("must-not-leak"))
        assertFalse(prompt.contains("private-revision-token"))
        assertFalse(prompt.contains("https://example.invalid"))
    }

    @Test fun absentCandidatesDoNotChangePromptOrInventFallback() {
        assertEquals("question", AgentChildWorkerAvailability.appendToPrompt("question", emptyList()))
        assertTrue(AgentChildWorkerAvailability.configuredChildren(listOf(unavailable)).isEmpty())
    }
}
