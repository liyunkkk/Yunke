package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.json.JSONArray

/** Model history comes from the worker, never from visible tool/reasoning summaries.
 * Only positively identified TEXT from the captured request can extend that closed boundary.
 * All work is on a temporary candidate; the running source is never rewritten.
 */
internal object AgentRunningBranchSnapshot {
    fun isTextForRound(id: String, runId: String, round: Int): Boolean =
        id == "assistant-$runId-$round" || id.startsWith("assistant-$runId-$round-")

    fun prepare(
        source: AgentChatHomeUiState,
        targetId: String,
        runId: String,
        round: Int,
        history: List<ConversationMessage>,
        textBaseline: Map<String, String>,
    ): AgentChatHomeUiState? {
        val index = source.messages.indices.singleOrNull { source.messages[it].id == targetId } ?: return null
        val target = source.messages[index]
        var candidate = source.copy(history = history)
        // Current-request images are transferred as provider media, while UI already owns
        // their durable file references. Restore only entries proven by BOTH reducers;
        // retain the worker's actual prompt (including runtime suffixes), never invent it.
        source.messages.filterIsInstance<UserMessageUi>().filter { it.images.isNotEmpty() }.forEach { user ->
            val modelBoundary = AgentConversationRevisionReducer.boundary(candidate, user.id,
                allowUnconsumedSupplement = false) ?: return@forEach
            val storedBoundary = AgentConversationRevisionReducer.boundary(source, user.id,
                allowUnconsumedSupplement = false) ?: return@forEach
            if (modelBoundary.logicalTurnId != storedBoundary.logicalTurnId) return@forEach
            val stored = source.history.getOrNull(storedBoundary.historyPrefix.size) ?: return@forEach
            val modelIndex = modelBoundary.historyPrefix.size
            val model = candidate.history.getOrNull(modelIndex) ?: return@forEach
            if (model.role != "user" || stored.role != "user" || model.turnId != stored.turnId ||
                AgentConversationCodec.persistedImageSources(model).isNotEmpty() || stored.contentJson.isBlank()) return@forEach
            val media = JSONArray(stored.contentJson)
            val files = (0 until media.length()).mapNotNull { media.optJSONObject(it) }
                .filter { it.optString("type") in setOf("image_file", "video_file") }
            if (files.isEmpty()) return@forEach
            val parts = if (model.contentJson.isBlank()) JSONArray().put(org.json.JSONObject()
                .put("type", "text").put("text", model.content)) else JSONArray(model.contentJson)
            val merged = JSONArray()
            // The codec's omitted-media notice must stay last for strict payload matching.
            val insertion = (0 until parts.length()).firstOrNull { i ->
                val part = parts.optJSONObject(i)
                part?.optString("type") == "text" &&
                    part.optString("text") == "[图片观察已在当前回合使用，未写入持久会话]"
            } ?: parts.length()
            for (i in 0..parts.length()) {
                if (i == insertion) files.forEach { merged.put(it) }
                if (i < parts.length()) merged.put(parts.get(i))
            }
            val repaired = model.copy(contentJson = merged.toString())
            candidate = candidate.copy(history = candidate.history.mapIndexed { i, item -> if (i == modelIndex) repaired else item })
        }
        val modelHistory = candidate.history
        if (target !is AgentMessageUi || !isTextForRound(target.id, runId, round)) return candidate
        if (target.content.isBlank()) return null
        val userIndex = (index downTo 0).firstOrNull { source.messages[it] is UserMessageUi } ?: return null
        val user = source.messages[userIndex] as UserMessageUi
        // A queued supplement not yet consumed by this request cannot authorize a new prefix.
        val boundary = AgentConversationRevisionReducer.boundary(candidate, user.id, allowUnconsumedSupplement = false) ?: return null
        if (modelHistory.lastOrNull()?.turnId != boundary.logicalTurnId) return null
        val peers = source.messages.subList(userIndex + 1, index + 1).filterIsInstance<AgentMessageUi>()
            .filter { isTextForRound(it.id, runId, round) }
        if (peers.isEmpty()) return null
        val allRoundText = source.messages.filterIsInstance<AgentMessageUi>()
            .filter { isTextForRound(it.id, runId, round) }
        if (allRoundText.map { it.id }.distinct().size != allRoundText.size) return null
        if (textBaseline.keys.any { id -> allRoundText.none { it.id == id } } ||
            allRoundText.any { !it.content.startsWith(textBaseline[it.id].orEmpty()) }) return null
        // A click may target an earlier block. Remove the entire consumed request tail,
        // then append only the TEXT prefix up to that target, not later output.
        val consumed = allRoundText.joinToString("") { textBaseline[it.id].orEmpty() }
        var end = modelHistory.size
        if (consumed.isNotEmpty()) {
            // Pause/resume can reuse a round and TEXT id. Replace only an exact text-only
            // trailing continuation chain; never cross a user supplement or any tool batch.
            var priorText = ""
            while (end > boundary.historyPrefix.size + 1 && priorText.length < consumed.length) {
                val message = modelHistory[end - 1]
                if (message.turnId != boundary.logicalTurnId) return null
                if (message.role == "assistant" && message.toolCallsJson.isBlank() && message.contentJson.isBlank()) {
                    priorText = message.content + priorText
                } else if (!(message.role == "user" && AgentContextCompactor.isSteeringUserMessage(message) &&
                        !message.content.trimStart().startsWith(AgentContextCompactor.STEERING_USER_PREFIX))) return null
                end--
            }
            if (priorText != consumed) return null
        }
        val additions = peers.map { ConversationMessage("assistant", it.content, turnId = boundary.logicalTurnId) }
        // Ordinary reducer remains responsible for exact target and closed tool batch checks.
        return candidate.copy(history = modelHistory.take(end) + additions)
    }
}
