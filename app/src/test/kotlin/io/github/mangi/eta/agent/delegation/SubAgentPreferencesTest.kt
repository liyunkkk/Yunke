package io.github.mangi.eta.agent.delegation

import android.app.Application
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SubAgentPreferencesTest {
    @Test fun additionalSlotsPreserveOriginalRoleAndModelReferences() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val original = SubAgentPreferences.selection(0)
        val review = SubAgentPreferences.selection(1)
        val saved = SubAgentPreferences.selection(3)
        val savedReasoning = SubAgentPreferences.reasoning(3)
        try {
            val extra = io.github.mangi.eta.agent.model.ModelFeatureSelection(true, "extra-provider", "extra-model")
            SubAgentPreferences.save(3, extra)
            assertEquals(extra, SubAgentPreferences.selection(3))
            assertEquals(original, SubAgentPreferences.selection(0))
            assertEquals(review, SubAgentPreferences.selection(1))
            assertEquals(listOf("implementation", "review", "implementation", "implementation"),
                (0 until SubAgentPreferences.SLOT_COUNT).map(SubAgentPreferences::role))
        } finally { SubAgentPreferences.save(3, saved); SubAgentPreferences.saveReasoning(3, savedReasoning) }
    }
    @Test fun draftSwitchPromotesOnceAndConversationsRemainIndependent() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val id = java.util.UUID.randomUUID().toString()
        assertTrue(SubAgentPreferences.enabled(id))
        SubAgentPreferences.setEnabled(null, false)
        SubAgentPreferences.promote(id)
        assertFalse(SubAgentPreferences.enabled(id))
        assertTrue(SubAgentPreferences.enabled(null))
        assertTrue(SubAgentPreferences.enabled("another-$id"))
    }
    @Test fun reasoningIsSlotLocalAndLeavesSharedModelAndParentSnapshotUnchanged() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val saved = (0 until SubAgentPreferences.SLOT_COUNT).map(SubAgentPreferences::reasoning)
        try {
            (0 until SubAgentPreferences.SLOT_COUNT).forEach { SubAgentPreferences.saveReasoning(it, null) }
            val shared = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                model = "same-model", systemPrompt = "", reasoningEffort = ReasoningEffort.LOW, thinkingEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true))
            val parentSnapshot = shared.copy()
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.saveReasoning(1, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.applyReasoning(0, shared).reasoningEffort)
            assertFalse(SubAgentPreferences.applyReasoning(1, shared).thinkingEnabled)
            assertSame(shared, SubAgentPreferences.applyReasoning(2, shared))
            assertEquals(parentSnapshot, shared)
            assertEquals(ReasoningEffort.LOW, parentSnapshot.reasoningEffort)
            val runningChild = SubAgentPreferences.applyReasoning(0, shared)
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.HIGH, runningChild.reasoningEffort)
            assertEquals(ReasoningEffort.OFF, SubAgentPreferences.applyReasoning(0, shared).reasoningEffort)
        } finally { saved.forEachIndexed(SubAgentPreferences::saveReasoning) }
    }

    @Test fun changingModelClearsOnlyThatSlotsOverride() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val savedModel = SubAgentPreferences.selection(0)
        val saved = (0..1).map(SubAgentPreferences::reasoning)
        try {
            val selection = ModelFeatureSelection(true, "provider-A", "model-A")
            SubAgentPreferences.save(0, selection)
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.saveReasoning(1, ReasoningEffort.LOW)
            SubAgentPreferences.save(0, selection)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.reasoning(0))
            SubAgentPreferences.save(0, selection.copy(modelId = "model-B"))
            assertNull(SubAgentPreferences.reasoning(0))
            assertEquals(ReasoningEffort.LOW, SubAgentPreferences.reasoning(1))
            SubAgentPreferences.saveReasoning(0, ReasoningEffort.HIGH)
            SubAgentPreferences.save(0, selection.copy(providerId = "provider-B", modelId = "model-B"))
            assertNull(SubAgentPreferences.reasoning(0))
        } finally {
            SubAgentPreferences.save(0, savedModel)
            saved.forEachIndexed(SubAgentPreferences::saveReasoning)
        }
    }

    @Test fun unsupportedOverridesAreNormalizedWithoutChangingStoredChoice() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val saved = SubAgentPreferences.reasoning(3)
        try {
            val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                model = "mandatory", systemPrompt = "", reasoningEffort = ReasoningEffort.LOW, thinkingEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), mandatory = true))
            SubAgentPreferences.saveReasoning(3, ReasoningEffort.OFF)
            assertEquals(ReasoningEffort.LOW, SubAgentPreferences.applyReasoning(3, config).reasoningEffort)
            SubAgentPreferences.saveReasoning(3, ReasoningEffort.XHIGH)
            assertEquals(ReasoningEffort.HIGH, SubAgentPreferences.applyReasoning(3, config).reasoningEffort)
            assertEquals(ReasoningEffort.XHIGH, SubAgentPreferences.reasoning(3))
            assertEquals(ReasoningEffort.OFF, SubAgentPreferences.applyReasoning(3, config.copy(reasoningCapabilities = null)).reasoningEffort)
        } finally { SubAgentPreferences.saveReasoning(3, saved) }
    }

    @Test fun legacyProfilesMigrateOnceAndDeletingAllDoesNotResurrectThem() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val saved = Prefs.getString(SubAgentPreferences.PROFILES_KEY)
        val provider = Prefs.getString("agent_child_2_provider")
        val model = Prefs.getString("agent_child_2_model")
        try {
            Prefs.putString(SubAgentPreferences.PROFILES_KEY, "")
            Prefs.putString("agent_child_2_provider", "legacy-provider")
            Prefs.putString("agent_child_2_model", "legacy-model")
            val migrated = SubAgentPreferences.profiles()
            assertEquals(listOf("legacy-0", "legacy-2", "legacy-3", "legacy-1"), migrated.map { it.id })
            assertEquals("legacy-model", migrated.single { it.id == "legacy-2" }.modelId)
            Prefs.putString("agent_child_2_model", "do-not-remigrate")
            assertEquals(migrated, SubAgentPreferences.profiles())
            migrated.forEach { SubAgentPreferences.remove(it.id) }
            assertTrue(SubAgentPreferences.profiles().isEmpty())
        } finally {
            Prefs.putString(SubAgentPreferences.PROFILES_KEY, saved)
            Prefs.putString("agent_child_2_provider", provider)
            Prefs.putString("agent_child_2_model", model)
        }
    }

    @Test fun dynamicProfilesKeepIdentityAndIndependentSettingsBeyondFour() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val saved = Prefs.getString(SubAgentPreferences.PROFILES_KEY)
        try {
            val added = List(8) { SubAgentPreferences.add() }
            assertEquals(8, added.map { it.id }.distinct().size)
            val first = added.first()
            val second = added[1]
            added.forEach { SubAgentPreferences.saveModel(it.id, ModelFeatureSelection(true, "p", "same-model")) }
            SubAgentPreferences.update(first.id) { it.copy(name = "审查重点", role = "review", tier = SubAgentTaskTier.COMPLEX,
                reasoning = ReasoningEffort.HIGH, enabled = false) }
            val updated = SubAgentPreferences.profiles().single { it.id == first.id }
            assertEquals("review", updated.role)
            assertNull(updated.tier)
            assertFalse(updated.enabled)
            assertNull(SubAgentPreferences.profiles().single { it.id == second.id }.reasoning)
            SubAgentPreferences.remove(first.id)
            SubAgentPreferences.update(first.id) { it.copy(name = "stale callback") }
            assertFalse(SubAgentPreferences.profiles().any { it.id == first.id })
            assertTrue(SubAgentPreferences.profiles().any { it.id == second.id })
            assertNull(updated.tier) // immutable running snapshot
        } finally { Prefs.putString(SubAgentPreferences.PROFILES_KEY, saved) }
    }

    @Test fun workerDescriptionContainsStableIdentityTierAndEffectiveReasoning() {
        val profile = SubAgentProfile("stable", "复杂实现", tier = SubAgentTaskTier.COMPLEX)
        val config = AgentModelClient.ModelConfig(baseUrl = "", apiKey = "never-advertise", model = "m", systemPrompt = "",
            reasoningEffort = ReasoningEffort.HIGH)
        val description = SubAgentPreferences.workerDescription(profile, 2, config)
        assertTrue(description.contains("2: agent_id=stable"))
        assertTrue(description.contains("complex"))
        assertTrue(description.contains("reasoning=high"))
        assertFalse(description.contains("never-advertise"))
    }

    @Test fun mediaRolesFilterModelsAndClearIncompatibleReferencesWhenRoleChanges() {
        val original = SubAgentProfile("id", "执行", providerId = "p", modelId = "m", reasoning = ReasoningEffort.HIGH)
        val review = original.withRole("review")
        assertEquals("m", review.modelId)
        assertEquals(ReasoningEffort.HIGH, review.reasoning)
        val image = original.withRole("image_generation")
        assertEquals("图片生成", image.roleLabel)
        assertTrue(image.isMedia)
        assertTrue(image.modelId.isBlank())
        assertNull(image.reasoning)
        assertTrue(image.acceptsModel(image = true, video = false))
        assertFalse(image.acceptsModel(image = false, video = true))
        val video = image.copy(providerId = "p", modelId = "image").withRole("video_generation")
        assertEquals("视频生成", video.roleLabel)
        assertTrue(video.modelId.isBlank())
        assertTrue(video.acceptsModel(image = false, video = true))
        assertFalse(video.acceptsModel(image = false, video = false))
        assertEquals(video, SubAgentProfile.fromJson(video.toJson()))
    }

    @Test fun nonImplementationRolesClearLegacyTiersOnReadWriteAndRoleChange() {
        val implementation = SubAgentProfile("test", "测试", tier = SubAgentTaskTier.COMPLEX)
        assertTrue(implementation.supportsTaskTier)
        assertEquals(SubAgentTaskTier.COMPLEX, implementation.withRole("implementation").tier)
        for (role in listOf("review", "image_generation", "video_generation")) {
            val changed = implementation.withRole(role)
            assertFalse(changed.supportsTaskTier)
            assertNull(changed.tier)
            assertNull(changed.withRole("implementation").tier)
            val legacyJson = implementation.toJson().put("role", role).put("tier", "complex")
            assertNull(SubAgentProfile.fromJson(legacyJson).tier)
            assertEquals("", implementation.copy(role = role).toJson().getString("tier"))
            val config = AgentModelClient.ModelConfig(baseUrl = "", apiKey = "", model = "m", systemPrompt = "")
            val description = SubAgentPreferences.workerDescription(implementation.copy(role = role), 1, config)
            assertTrue(description.contains("task tier=not applicable"))
            assertFalse(description.contains("complex"))
            assertFalse(description.contains("suited tasks"))
        }
    }

}
