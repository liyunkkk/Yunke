package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class RuntimeSelectionDedupTest {
    private val model = Model("selection", "api-model", "Model", createdAt = 1L)
    private val provider = OpenAiCompatibleProviderSetting("p", "Provider", "https://example.invalid", models = listOf(model), createdAt = 1L)
    private fun tuple(providers: List<ProviderSetting>, providerId: String? = "p", modelId: String? = "selection") =
        Triple(providerId, modelId, providers)

    @Test fun preferencesOnlyReemissionsAndEqualDatabaseReloadsAreSuppressed() = runBlocking {
        val providers = listOf<ProviderSetting>(provider)
        val first = tuple(providers)
        val equalReload = tuple(listOf(provider.copy(models = listOf(model.copy()))))
        assertTrue(runtimeSelectionUnchanged(first, tuple(providers)))
        assertTrue(runtimeSelectionUnchanged(first, equalReload))
        assertEquals(listOf(first), listOf(first, tuple(providers), equalReload).asFlow()
            .distinctUntilChanged(::runtimeSelectionUnchanged).toList())
    }

    @Test fun realSelectionAndProviderModelOrConfigChangesAlwaysReachProjection() = runBlocking {
        val first = tuple(listOf(provider))
        val changes = listOf(
            tuple(listOf(provider), providerId = null), tuple(listOf(provider), modelId = "other"),
            tuple(listOf(provider.copy(isEnabled = false))), tuple(listOf(provider.copy(apiKey = "test-key"))),
            tuple(listOf(provider.copy(baseUrl = "https://changed.invalid"))),
            tuple(listOf(provider.copy(endpointMode = "responses"))),
            tuple(listOf(provider.copy(sessionGatewayJson = "{}"))),
            tuple(listOf(provider.copy(models = listOf(model.copy(contextWindowOverride = 8192))))),
            tuple(listOf(provider.copy(models = listOf(model.copy(isEnabled = false))))),
            tuple(listOf(provider.copy(models = listOf(model.copy(displayName = "Renamed"))))),
            tuple(listOf(provider.copy(models = listOf(model.copy(modelId = "new-api-id"))))),
            tuple(emptyList()),
        )
        changes.forEach { assertFalse(runtimeSelectionUnchanged(first, it)) }
        val emissions = listOf(first) + changes
        assertEquals(emissions, emissions.asFlow().distinctUntilChanged(::runtimeSelectionUnchanged).toList())
    }

    @Test fun providerOrderIsNotCollapsedToAnIdSet() {
        val other = provider.copy(id = "other")
        assertFalse(runtimeSelectionUnchanged(tuple(listOf(provider, other)), tuple(listOf(other, provider))))
    }
}
