package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking {
            SettingsDataStore.setSelection(providerId = null, modelId = null)
        }
    }

    @Test
    fun saveModelCommitsValidatedManualDraftOnlyOnce() = runBlocking {
        addEmptyProvider()
        val draft = Model(id = "", modelId = "  custom-model  ", displayName = "  自定义模型  ")

        assertTrue(ModelRepository.modelsByProvider(PROVIDER_ID).isEmpty())
        val saved = ModelRepository.saveModel(PROVIDER_ID, draft)

        val restored = ModelRepository.modelsByProvider(PROVIDER_ID).single()
        assertEquals(saved.id, restored.id)
        assertEquals("custom-model", restored.modelId)
        assertEquals("自定义模型", restored.displayName)
        assertEquals(ModelSource.MANUAL, restored.source)
    }

    @Test
    fun saveModelRejectsBlankAndDuplicateIdsWithoutChangingStorage() = runBlocking {
        addEmptyProvider()
        ModelRepository.saveModel(
            PROVIDER_ID,
            Model(id = "", modelId = "model-a", displayName = "Model A"),
        )

        val blankFailure = runCatching {
            ModelRepository.saveModel(
                PROVIDER_ID,
                Model(id = "", modelId = " ", displayName = "空模型"),
            )
        }
        val duplicateFailure = runCatching {
            ModelRepository.saveModel(
                PROVIDER_ID,
                Model(id = "", modelId = "MODEL-A", displayName = "重复模型"),
            )
        }

        assertTrue(blankFailure.isFailure)
        assertTrue(duplicateFailure.isFailure)
        assertEquals(listOf("model-a"), ModelRepository.modelsByProvider(PROVIDER_ID).map { it.modelId })
    }

    @Test
    fun remoteSyncPreservesManualAndCatalogModelsAndOnlyRemovesStaleRemoteModels() = runBlocking {
        ProviderRepository.addProvider(
            provider(
                models = listOf(
                    Model(
                        id = "manual-id",
                        modelId = "manual-model",
                        displayName = "Manual",
                        contextWindow = 128_000,
                        contextWindowOverride = 256_000,
                        reasoning = true,
                        reasoningCapabilities = ModelReasoningCapabilities(
                            supportedEfforts = listOf(ReasoningEffort.HIGH),
                        ),
                        reasoningOverride = true,
                        reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                            supportedEfforts = listOf(ReasoningEffort.MINIMAL),
                            canDisable = true,
                        ),
                        source = ModelSource.MANUAL,
                    ),
                    Model(
                        id = "catalog-id",
                        modelId = "catalog-model",
                        displayName = "Catalog",
                        source = ModelSource.CATALOG,
                        isBuiltIn = true,
                    ),
                    Model(
                        id = "stale-id",
                        modelId = "remote-stale",
                        displayName = "Stale",
                        source = ModelSource.REMOTE,
                    ),
                )
            )
        )

        val result = ModelRepository.syncRemoteModels(
            PROVIDER_ID,
            listOf(
                Model(
                    id = "remote-manual-match",
                    modelId = "manual-model",
                    displayName = "Manual From Remote",
                    contextWindow = 1_000_000,
                    toolCall = true,
                    source = ModelSource.REMOTE,
                ),
                Model(
                    id = "remote-new",
                    modelId = "remote-new",
                    displayName = "Remote New",
                    source = ModelSource.REMOTE,
                ),
            ),
        )

        assertTrue(result.applied)
        assertEquals(1, result.addedCount)
        assertEquals(1, result.removedCount)
        val restored = ModelRepository.modelsByProvider(PROVIDER_ID).associateBy { it.modelId }
        assertEquals(setOf("manual-model", "catalog-model", "remote-new"), restored.keys)
        assertFalse("remote-stale" in restored)
        assertEquals(
            setOf("remote-new"),
            restored.values.filter { it.source == ModelSource.REMOTE }.mapTo(mutableSetOf()) { it.modelId },
        )
        assertEquals(ModelSource.MANUAL, restored.getValue("manual-model").source)
        assertTrue(restored.getValue("manual-model").supportsTools)
        assertEquals(1_000_000, restored.getValue("manual-model").contextWindow)
        assertEquals(256_000, restored.getValue("manual-model").effectiveContextWindow)
        assertEquals(
            listOf(ReasoningEffort.OFF, ReasoningEffort.MINIMAL),
            restored.getValue("manual-model").effectiveReasoningCapabilities?.selectableEfforts,
        )
        assertEquals(ModelSource.CATALOG, restored.getValue("catalog-model").source)
        assertEquals(ModelSource.REMOTE, restored.getValue("remote-new").source)

        val emptyResult = ModelRepository.syncRemoteModels(PROVIDER_ID, emptyList())
        assertFalse(emptyResult.applied)
        assertEquals(restored.keys, ModelRepository.modelsByProvider(PROVIDER_ID).mapTo(mutableSetOf()) { it.modelId })
    }

    @Test
    fun remoteSyncWithoutNewModelsShrinksTenRemoteModelsToFive() = runBlocking {
        val original = (1..10).map { remoteModel(it) }
        ProviderRepository.addProvider(provider(models = original))

        val result = ModelRepository.syncRemoteModels(
            PROVIDER_ID,
            original.take(5),
            includeNewModels = false,
        )

        assertTrue(result.applied)
        assertEquals(5, result.fetchedCount)
        assertEquals(0, result.addedCount)
        assertEquals(5, result.removedCount)
        // providerById also supplies local TTS catalog entries; count only REMOTE models.
        val remainingRemote = ModelRepository.modelsByProvider(PROVIDER_ID)
            .filter { it.source == ModelSource.REMOTE }
        assertEquals(original.take(5).map { it.id }, remainingRemote.map { it.id })
        assertEquals(original.take(5).map { it.modelId }, remainingRemote.map { it.modelId })
    }

    @Test
    fun remoteSyncWithoutNewModelsPreservesMatchingLocalConfiguration() = runBlocking {
        val stored = remoteModel(1).copy(
            isEnabled = false,
            customHeaders = listOf(CustomHeader("x-model", "local-value")),
            contextWindowOverride = 256_000,
            reasoningOverride = true,
            visionOverride = false,
            reasoningCapabilitiesOverride = ModelReasoningCapabilities(
                supportedEfforts = listOf(ReasoningEffort.MINIMAL),
                canDisable = true,
            ),
            preferredReasoningEffort = ReasoningEffort.MINIMAL,
        )
        ProviderRepository.addProvider(provider(models = listOf(stored)))
        val remote = remoteModel(1).copy(
            id = "different-remote-id",
            modelId = "  REMOTE-1  ",
            displayName = "Updated Remote Name",
            contextWindow = 1_000_000,
            customHeaders = listOf(CustomHeader("x-model", "remote-value")),
            contextWindowOverride = 128_000,
            reasoningOverride = false,
            visionOverride = true,
            preferredReasoningEffort = ReasoningEffort.HIGH,
        )

        val result = ModelRepository.syncRemoteModels(
            PROVIDER_ID,
            listOf(remote),
            includeNewModels = false,
        )

        assertTrue(result.applied)
        assertEquals(0, result.addedCount)
        assertEquals(0, result.removedCount)
        val restored = ModelRepository.modelsByProvider(PROVIDER_ID).single { it.id == stored.id }
        assertEquals(stored.id, restored.id)
        assertEquals(stored.isEnabled, restored.isEnabled)
        assertEquals(stored.isBuiltIn, restored.isBuiltIn)
        assertEquals(stored.customHeaders, restored.customHeaders)
        assertEquals(stored.customBody, restored.customBody)
        assertEquals(stored.contextWindowOverride, restored.contextWindowOverride)
        assertEquals(stored.reasoningOverride, restored.reasoningOverride)
        assertEquals(stored.visionOverride, restored.visionOverride)
        assertEquals(stored.reasoningCapabilitiesOverride, restored.reasoningCapabilitiesOverride)
        assertEquals(stored.preferredReasoningEffort, restored.preferredReasoningEffort)
        assertEquals(stored.source, restored.source)
        assertEquals(stored.createdAt, restored.createdAt)
        assertEquals("REMOTE-1", restored.modelId)
        assertEquals(remote.displayName, restored.displayName)
        assertEquals(remote.contextWindow, restored.contextWindow)
    }

    @Test
    fun remoteSyncWithoutNewModelsProtectsAbsentManualAndCatalogModels() = runBlocking {
        val manual = Model(
            id = "manual-id",
            modelId = "manual-only",
            displayName = "Manual",
            source = ModelSource.MANUAL,
            contextWindowOverride = 256_000,
        )
        val catalog = Model(
            id = "catalog-id",
            modelId = "catalog-only",
            displayName = "Catalog",
            source = ModelSource.CATALOG,
            isBuiltIn = true,
        )
        ProviderRepository.addProvider(
            provider(models = listOf(manual, catalog, remoteModel(1), remoteModel(2)))
        )

        val result = ModelRepository.syncRemoteModels(
            PROVIDER_ID,
            listOf(remoteModel(1), remoteModel(3)),
            includeNewModels = false,
        )

        assertEquals(0, result.addedCount)
        assertEquals(1, result.removedCount)
        val restored = ModelRepository.modelsByProvider(PROVIDER_ID).associateBy { it.modelId }
        assertEquals(manual.id, restored.getValue(manual.modelId).id)
        assertEquals(ModelSource.MANUAL, restored.getValue(manual.modelId).source)
        assertEquals(manual.contextWindowOverride, restored.getValue(manual.modelId).contextWindowOverride)
        assertEquals(catalog.id, restored.getValue(catalog.modelId).id)
        assertEquals(ModelSource.CATALOG, restored.getValue(catalog.modelId).source)
        assertTrue(restored.getValue(catalog.modelId).isBuiltIn)
        assertFalse("remote-2" in restored)
        assertFalse("remote-3" in restored)
    }

    @Test
    fun remoteSyncWithoutNewModelsLeavesCandidatesForSelectiveAddition() = runBlocking {
        ProviderRepository.addProvider(provider(models = listOf(remoteModel(1), remoteModel(4))))
        val fetched = listOf(remoteModel(1), remoteModel(2), remoteModel(3))

        val result = ModelRepository.syncRemoteModels(PROVIDER_ID, fetched, includeNewModels = false)
        val existingKeys = ModelRepository.modelsByProvider(PROVIDER_ID)
            .map { it.modelId.trim().lowercase() }.toSet()
        val candidates = fetched.filter { it.modelId.trim().lowercase() !in existingKeys }

        assertEquals(0, result.addedCount)
        assertEquals(1, result.removedCount)
        assertEquals(listOf("remote-2", "remote-3"), candidates.map { it.modelId })
        assertEquals(1, ModelRepository.addSelectedRemoteModels(PROVIDER_ID, listOf(candidates.first())))
        assertEquals(
            listOf("remote-1", "remote-2"),
            ModelRepository.modelsByProvider(PROVIDER_ID)
                .filter { it.source == ModelSource.REMOTE }.map { it.modelId },
        )
        assertEquals(0, ModelRepository.addSelectedRemoteModels(PROVIDER_ID, listOf(candidates.first())))
    }

    @Test
    fun remoteSyncWithoutNewModelsIsIdempotent() = runBlocking {
        ProviderRepository.addProvider(provider(models = (1..3).map { remoteModel(it) }))
        val fetched = listOf(remoteModel(1), remoteModel(4))
        ModelRepository.syncRemoteModels(PROVIDER_ID, fetched, includeNewModels = false)
        val afterFirst = ModelRepository.modelsByProvider(PROVIDER_ID)

        val repeated = ModelRepository.syncRemoteModels(PROVIDER_ID, fetched, includeNewModels = false)

        assertTrue(repeated.applied)
        assertEquals(0, repeated.addedCount)
        assertEquals(0, repeated.removedCount)
        assertEquals(afterFirst, ModelRepository.modelsByProvider(PROVIDER_ID))
    }

    @Test
    fun remoteSyncWithoutNewModelsIgnoresEmptyOrBlankSnapshots() = runBlocking {
        ProviderRepository.addProvider(provider(models = (1..3).map { remoteModel(it) }))
        val before = ModelRepository.modelsByProvider(PROVIDER_ID)
        val empty = ModelRepository.syncRemoteModels(PROVIDER_ID, emptyList(), includeNewModels = false)
        val unusable = ModelRepository.syncRemoteModels(
            PROVIDER_ID,
            listOf(Model(id = "blank-id", modelId = "  ", displayName = "Blank")),
            includeNewModels = false,
        )

        assertFalse(empty.applied)
        assertFalse(unusable.applied)
        assertEquals(0, empty.removedCount)
        assertEquals(0, unusable.removedCount)
        assertEquals(before, ModelRepository.modelsByProvider(PROVIDER_ID))
    }

    @Test
    fun saveModelPersistsPreferredReasoningEffortPerModel() = runBlocking {
        addEmptyProvider()
        val first = ModelRepository.saveModel(
            PROVIDER_ID,
            Model(id = "", modelId = "model-a", displayName = "Model A"),
        )
        val second = ModelRepository.saveModel(
            PROVIDER_ID,
            Model(id = "", modelId = "model-b", displayName = "Model B"),
        )

        ModelRepository.saveModel(
            PROVIDER_ID,
            first.copy(preferredReasoningEffort = ReasoningEffort.HIGH),
        )
        ModelRepository.saveModel(
            PROVIDER_ID,
            second.copy(preferredReasoningEffort = ReasoningEffort.LOW),
        )

        val restored = ModelRepository.modelsByProvider(PROVIDER_ID).associateBy { it.modelId }
        assertEquals(ReasoningEffort.HIGH, restored.getValue("model-a").preferredReasoningEffort)
        assertEquals(ReasoningEffort.LOW, restored.getValue("model-b").preferredReasoningEffort)
    }

    @Test
    fun providerConfigSaveDoesNotOverwriteModelsAddedFromAnotherDraft() = runBlocking {
        addEmptyProvider()
        val staleProviderDraft = ProviderRepository.providerById(PROVIDER_ID)!!
        ModelRepository.saveModel(
            PROVIDER_ID,
            Model(id = "", modelId = "model-a", displayName = "Model A"),
        )

        ProviderRepository.updateProvider(
            (staleProviderDraft as CustomProviderSetting).copy(apiKey = "new-key")
        )

        val restored = ProviderRepository.providerById(PROVIDER_ID) as CustomProviderSetting
        assertEquals("new-key", restored.apiKey)
        assertEquals(listOf("model-a"), restored.models.map { it.modelId })
    }

    private fun remoteModel(number: Int): Model = Model(
        id = "remote-id-$number",
        modelId = "remote-$number",
        displayName = "Remote $number",
        source = ModelSource.REMOTE,
        sortOrder = number - 1,
        createdAt = 1_000L + number,
    )

    private suspend fun addEmptyProvider() {
        ProviderRepository.addProvider(provider())
    }

    private fun provider(models: List<Model> = emptyList()): CustomProviderSetting =
        CustomProviderSetting(
            id = PROVIDER_ID,
            name = "Test Provider",
            baseUrl = "https://example.com/v1",
            models = models,
        )

    private companion object {
        const val PROVIDER_ID = "provider-test"
    }
}
