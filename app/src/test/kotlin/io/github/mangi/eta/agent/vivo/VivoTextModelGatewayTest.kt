package io.github.mangi.eta.agent.vivo

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.CustomBody
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VivoTextModelGatewayTest {
    private val config = AgentModelClient.ModelConfig(providerId = "p", baseUrl = "https://example.invalid/v1",
        apiKey = "test-only", model = "selected-model", systemPrompt = "existing persona", assistantId = "assistant",
        hostedWebSearchEnabled = true, terminalTools = true, browserTools = true,
        deviceDirectTools = true, deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
        supportsVision = true, supportsVideo = true)
    private val selected = VivoTextModelGateway.Selection("p", "model-uuid")

    @Test fun exactSelectionLoadsOnlyOnceAndReturnsTextOnlyCopy() = runBlocking {
        var calls = 0
        val safe = VivoTextModelGateway.resolve({ selected }) { provider, model ->
            calls++
            assertEquals("p", provider)
            assertEquals("model-uuid", model)
            config
        }!!
        assertEquals(1, calls)
        assertFalse(safe.hostedWebSearchEnabled)
        assertFalse(safe.terminalTools)
        assertFalse(safe.browserTools)
        assertFalse(safe.deviceDirectTools)
        assertFalse(safe.deviceSensitiveReadTools)
        assertFalse(safe.deviceSensitiveActionTools)
        assertFalse(safe.supportsVision)
        assertFalse(safe.supportsVideo)
        assertEquals(2048, safe.summaryOutputLimit)
        assertEquals("", safe.systemPrompt)
        assertEquals("", safe.assistantId)
        assertTrue(config.hostedWebSearchEnabled) // never mutate the stored config
    }

    @Test fun missingSelectionDoesNotLoadOrPickAnotherModel() = runBlocking {
        listOf(VivoTextModelGateway.Selection(null, "model"),
            VivoTextModelGateway.Selection("p", null), VivoTextModelGateway.Selection("", "model"),
            VivoTextModelGateway.Selection("p", ""), VivoTextModelGateway.Selection("p", " "),
            VivoTextModelGateway.Selection(" ", "model")).forEach {
            assertNull(VivoTextModelGateway.resolve({ it }) { _, _ -> error("must not select fallback") })
        }
    }

    @Test fun deletedOrDisabledModelDoesNotFallBack() = runBlocking {
        var calls = 0
        assertNull(VivoTextModelGateway.resolve({ selected }) { _, _ -> calls++; null })
        assertEquals(1, calls)
    }

    @Test fun selectionChangedDuringOAuthOrLoadingIsRejected() = runBlocking {
        var reads = 0
        assertNull(VivoTextModelGateway.resolve({ if (reads++ == 0) selected else selected.copy(modelId = "new") })
            { _, _ -> config })
    }

    @Test fun wrongProviderIsRejected() = runBlocking {
        assertNull(VivoTextModelGateway.resolve({ selected }) { _, _ -> config.copy(providerId = "other") })
    }

    @Test fun unsafeLoadedConfigIsNotSilentlyRewrittenOrSent() = runBlocking {
        assertNull(VivoTextModelGateway.resolve({ selected }) { _, _ -> config.copy(extraBodyJson = "{}") })
    }

    @Test fun loaderFailureReachesServiceFixedErrorBoundaryWithoutFallback() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                VivoTextModelGateway.resolve({ selected }) { _, _ -> calls++; error("simulated config failure") }
            }
        }
        assertEquals(1, calls)
    }

    @Test fun invalidCredentialsAndExtraRequestBodyAreRejected() {
        assertNull(VivoTextModelGateway.textOnly(config.copy(apiKey = "")))
        assertNull(VivoTextModelGateway.textOnly(config.copy(model = "")))
        assertNull(VivoTextModelGateway.textOnly(config.copy(baseUrl = "")))
        assertNull(VivoTextModelGateway.textOnly(config.copy(extraBodyJson = "{\"tools\":[]}")))
        assertNull(VivoTextModelGateway.textOnly(config.copy(extraBodyJson = "{}")))
        assertNull(VivoTextModelGateway.textOnly(config.copy(customBody = listOf(CustomBody("tools", JsonPrimitive("override"))))))
        assertNull(VivoTextModelGateway.textOnly(config.copy(customBody = listOf(CustomBody("temperature", JsonPrimitive(0.1))))))
    }
}
