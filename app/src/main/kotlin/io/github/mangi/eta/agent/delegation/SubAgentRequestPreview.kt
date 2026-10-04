package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentChildWorkerAvailability
import io.github.mangi.eta.agent.runtime.ChildWorkerConfigResolver
import io.github.mangi.eta.agent.runtime.ExistingChildTaskTools
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import org.json.JSONArray

/**
 * Local-only preview of RunExecutor's selected workers. Inject BOTH resolver lookups so this
 * path cannot refresh OAuth, repair a selection, allocate a coordinator/worktree or send a request.
 * Uses the same availability checks, worker descriptions, schema and availability prompt as a run.
 * Expired/missing credentials may still change availability when the real run resolves them.
 */
internal object SubAgentRequestPreview {
    suspend fun appendTo(
        tools: JSONArray,
        owner: SubAgentConfigKey,
        config: ConversationSubAgentConfig,
        providers: List<ProviderSetting>,
        workspaceEnabled: Boolean,
    ): String {
        val candidates = ChildWorkerConfigResolver.resolve(
            ownerId = owner.value,
            config = config,
            providerLookup = { id -> providers.firstOrNull { it.id == id } },
            modelResolver = { profile ->
                val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
                val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
                if (provider == null || model == null) null
                else RuntimeConfigRepository.buildRuntimeConfig(provider, model)
            },
        )
        val configured = AgentChildWorkerAvailability.configuredChildren(candidates)
        if (configured.isEmpty()) {
            ExistingChildTaskTools.appendTo(tools)
        } else {
            SubAgentTools.appendTo(tools, configured.mapIndexed { index, (profile, model) ->
                SubAgentPreferences.workerDescription(profile, index + 1, model,
                    config.parallelLimit(SubAgentParallelModel(model.providerId, model.model)))
            }, workspaceEnabled = workspaceEnabled)
        }
        // This suffix is appended to the user's prompt, not stored in visible conversation history.
        return AgentChildWorkerAvailability.appendToPrompt("", candidates)
    }
}
