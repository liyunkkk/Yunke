package io.github.mangi.eta.agent.pet

import android.content.Context
import io.github.mangi.eta.agent.model.ModelFeatureCompletion
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal object WhaleMaidSpeaker {
    fun speak(
        context: Context,
        eventType: String,
        satiety: Int,
        foodName: String,
        tokensGained: Int,
        sessionTitle: String,
        recentTasks: List<String>,
        memories: List<String>,
    ): Pair<WhaleMaidReaction, Int> {
        val prompt = whaleMaidPrompt(
            eventType = eventType,
            satiety = satiety,
            foodName = foodName,
            tokensGained = tokensGained,
            sessionTitle = sessionTitle,
            recentTasks = recentTasks,
            memories = memories,
        )
        val raw = runCatching { complete(context, prompt) }.getOrNull().orEmpty()
        val reaction = parseWhaleMaidReaction(raw, eventType, satiety, foodName, sessionTitle)
        return reaction to whaleMaidEstimateTokens(prompt, reaction.speech)
    }

    private fun complete(context: Context, prompt: String): String {
        val current = runBlocking(Dispatchers.IO) { RuntimeConfigRepository.currentRuntimeConfig() }
            ?: error("no current model")
        val selection = WhaleMaidStore.modelSelection(context)
        val chosen = if (!selection.custom) {
            current
        } else {
            runBlocking(Dispatchers.IO) { selection.resolve() } ?: current
        }
        val bounded = chosen.copy(
            systemPrompt = prompt,
            hostedWebSearchEnabled = false,
            thinkingEnabled = false,
            errorReconnectPolicy = ErrorReconnectPolicy.NONE.persistedValue,
        )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", prompt))
            .put(JSONObject().put("role", "user").put("content", "请按规则给出当前反应的 JSON。全程绝对禁止任何emoji。"))
        return ModelFeatureCompletion.complete(
            config = bounded,
            messages = messages,
            controller = AgentRunController(),
            sessionId = "whale-maid",
            timeoutMs = 45_000,
            outputLimit = 120,
            usageConversationId = "whale-maid",
        )
    }
}
