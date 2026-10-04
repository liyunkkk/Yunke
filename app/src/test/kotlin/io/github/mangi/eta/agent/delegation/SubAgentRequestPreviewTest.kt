package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentRequestOverhead
import io.github.mangi.eta.agent.runtime.ExistingChildTaskTools
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentRequestPreviewTest {
    private val owner = SubAgentConfigKey.Draft("selected-draft")
    private val profile = SubAgentProfile("chosen-worker", "Chosen review worker", role = "review",
        providerId = "p", modelId = "selected")
    private val provider = OpenAiCompatibleProviderSetting("p", "Selected provider", "https://example.invalid/v1",
        apiKey = "private-credential", models = listOf(Model("selected", "api-model", "Display model")))
    private val model = RuntimeConfigRepository.buildRuntimeConfig(provider, provider.models.single())
    private val config = ConversationSubAgentConfig(listOf(profile),
        parallelLimits = mapOf(SubAgentParallelModel("p", "api-model") to 4))

    @Test fun previewMatchesRuntimeSchemaAndDescriptionAndPreservesMcpTools() = runBlocking {
        val sentinel = JSONObject().put("type", "function").put("function", JSONObject().put("name", "mcp_test"))
        val tools = JSONArray().put(sentinel)
        val prompt = SubAgentRequestPreview.appendTo(tools, owner, config, listOf(provider), workspaceEnabled = true)
        val expected = JSONArray().put(sentinel)
        SubAgentTools.appendTo(expected, listOf(SubAgentPreferences.workerDescription(profile, 1, model, 4)),
            workspaceEnabled = true)
        assertEquals(expected.toString(), tools.toString())
        assertEquals(SubAgentTools.names + "mcp_test", names(tools))
        assertTrue(tools.toString().contains("Chosen review worker"))
        assertTrue(tools.toString().contains("parallel limit=4"))
        assertTrue(prompt.contains("chosen-worker"))
        assertTrue(prompt.contains("AVAILABLE"))
        assertTrue(AgentContextBudget.countTokens(prompt) > 0)
        assertFalse((tools.toString() + prompt).contains("private-credential"))
        assertFalse((tools.toString() + prompt).contains("https://example.invalid"))
        assertTrue(AgentRequestOverhead.estimate(model, additionalTools = tools) >
            AgentRequestOverhead.estimate(model, additionalTools = JSONArray().put(sentinel)))
    }

    @Test fun noTerminalOmitsWorkspaceToolForConfiguredWorkers() = runBlocking {
        val tools = JSONArray()
        SubAgentRequestPreview.appendTo(tools, owner, config, listOf(provider), workspaceEnabled = false)
        assertEquals(SubAgentTools.names - "manage_agent_workspace", names(tools))
    }

    @Test fun disabledEmptyAndUnavailableSelectionsUseExistingTaskSchemasNotDelegation() = runBlocking {
        val cases = listOf(config.copy(enabled = false), config.copy(profiles = emptyList()),
            config.copy(profiles = listOf(profile.copy(modelId = "removed"))),
            config.copy(profiles = listOf(profile.copy(enabled = false))))
        cases.forEach { selected ->
            val tools = JSONArray()
            val prompt = SubAgentRequestPreview.appendTo(tools, owner, selected, listOf(provider), workspaceEnabled = false)
            val expected = JSONArray().also(ExistingChildTaskTools::appendTo)
            assertEquals(expected.toString(), tools.toString())
            assertFalse(names(tools).contains("delegate_task"))
            if (selected.profiles.isEmpty()) assertEquals("", prompt)
            else assertTrue(prompt.contains("configuration_available"))
        }
    }

    @Test fun exactSelectionDoesNotFallBackAndAvailableWorkersKeepFilteredOrder() = runBlocking {
        val missing = profile.copy(id = "missing", modelId = "not-selected")
        val tools = JSONArray()
        val prompt = SubAgentRequestPreview.appendTo(tools, owner, config.copy(profiles = listOf(missing, profile)),
            listOf(provider), workspaceEnabled = false)
        val expected = JSONArray().also {
            SubAgentTools.appendTo(it, listOf(SubAgentPreferences.workerDescription(profile, 1, model, 4)))
        }
        assertEquals(expected.toString(), tools.toString())
        assertTrue(prompt.contains("MODEL_UNAVAILABLE"))
        assertTrue(prompt.contains("missing"))
        val otherTools = JSONArray()
        val otherPrompt = SubAgentRequestPreview.appendTo(otherTools, SubAgentConfigKey.Conversation("other"),
            config.copy(profiles = listOf(missing)), listOf(provider), workspaceEnabled = false)
        assertFalse(names(otherTools).contains("delegate_task"))
        assertFalse(otherPrompt.contains("chosen-worker"))
    }

    private fun names(tools: JSONArray): Set<String> = (0 until tools.length())
        .map { tools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
}
