package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationFolderEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.attachUserImageSources
import io.github.mangi.eta.ui.model.decodeUserMessageImages
import io.github.mangi.eta.ui.model.encodeUserMessageImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray

internal object AgentConversationStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class Snapshot(
        val selectedConversationId: String?,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
        val folderIds: Map<String, String> = emptyMap(),
        val pinnedIds: Set<String> = emptySet(),
        val folders: List<ConversationFolderUi> = emptyList(),
    )

    private val saveMutex = Mutex()

    fun load(context: Context): Snapshot =
        runBlocking(Dispatchers.IO) {
            loadSnapshot(context.applicationContext)
        }

    data class AssistantConversationData(
        val conversationId: String,
        val title: String,
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
        val updatedAt: Long = 0L,
    )

    suspend fun loadAssistantConversation(context: Context, conversationId: String? = null): AssistantConversationData? {
        val dao = EtaDatabase.get(context.applicationContext).conversationDao()
        val targetId = conversationId
            ?: dao.state()?.selectedConversationId
            ?: dao.conversationsPage(limit = 1, offset = 0).firstOrNull()?.id
            ?: return null
        val metadata = dao.conversationEntity(targetId) ?: return null
        val messageEntities = buildList {
            var offset = 0
            while (true) {
                val page = dao.messagesPage(
                    conversationId = targetId,
                    limit = MESSAGE_LOAD_PAGE_SIZE,
                    offset = offset,
                )
                addAll(page)
                if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                offset += page.size
            }
        }.sortedBy { it.sortIndex }
        val messages = messageEntities.mapNotNull { it.toMessageOrNull() }.distinctBy { it.id }
        val history = AgentConversationCodec.decodeTranscript(dao.contextCheckpoint(targetId)?.historyJson)
            .ifEmpty { messageEntities.toLegacyHistory() }
        return AssistantConversationData(
            conversationId = targetId,
            title = metadata.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty(),
            messages = messages,
            history = history,
            updatedAt = metadata.updatedAt,
        )
    }

    suspend fun saveAssistantConversation(
        context: Context,
        conversationId: String,
        title: String,
        messages: List<AgentChatMessageUi>,
        history: List<AgentModelClient.ConversationMessage>,
    ) {
        val appContext = context.applicationContext
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                val dao = EtaDatabase.get(appContext).conversationDao()
                val now = System.currentTimeMillis()
                val existing = dao.conversationEntity(conversationId)
                val conversationEntity = ConversationEntity(
                    id = conversationId,
                    title = title.ifBlank { existing?.title.orEmpty() },
                    thinkingEnabled = existing?.thinkingEnabled ?: false,
                    reasoningEffort = existing?.reasoningEffort ?: ReasoningEffort.DEFAULT.wireValue,
                    appliedRuntimeRunIdsJson = existing?.appliedRuntimeRunIdsJson ?: "[]",
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                val messageEntities = messages.distinctBy { it.id }
                    .mapIndexedNotNull { index, msg -> msg.toEntityOrNull(conversationId, index) }
                val checkpoint = ConversationContextCheckpointEntity(
                    conversationId = conversationId,
                    historyJson = encodeCheckpoint(history),
                )
                dao.upsertConversation(conversationEntity, messageEntities, checkpoint)
                dao.insertState(ConversationStateEntity(selectedConversationId = conversationId))
            }
        }
    }

    suspend fun selectConversation(context: Context, conversationId: String) {
        withContext(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).conversationDao()
                .insertState(ConversationStateEntity(selectedConversationId = conversationId))
        }
    }

    suspend fun loadRecentConversations(context: Context, limit: Int = 30): List<ConversationMetadata> =
        withContext(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).conversationDao()
                .conversationsPage(limit = limit, offset = 0)
        }

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
        folderIds: Map<String, String> = emptyMap(),
        pinnedIds: Set<String> = emptySet(),
        folders: List<ConversationFolderUi> = emptyList(),
    ) {
        val appContext = context.applicationContext
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                val sorted = conversationsById.entries
                    .sortedByDescending { (id, _) -> updatedAt[id] ?: 0L }

                val storedIds = sorted.mapTo(mutableSetOf()) { it.key }
                val selected = selectedConversationId
                    ?.takeIf { it in storedIds }
                    ?: sorted.firstOrNull()?.key
                val now = System.currentTimeMillis()
                val conversations = sorted.map { (id, state) ->
                    ConversationEntity(
                        id = id,
                        title = titles[id].orEmpty(),
                        thinkingEnabled = state.reasoningEffort.enablesReasoning,
                        reasoningEffort = state.reasoningEffort.wireValue,
                        appliedRuntimeRunIdsJson = json.encodeToString(state.appliedRuntimeRunIds),
                        createdAt = updatedAt[id] ?: now,
                        updatedAt = updatedAt[id] ?: now,
                        folderId = folderIds[id].orEmpty(),
                        isPinned = id in pinnedIds,
                        providerId = state.providerId,
                        modelId = state.modelId,
                        assistantId = state.assistantId,
                    )
                }
                val messages = sorted.flatMap { (conversationId, state) ->
                    state.messages
                        .mapIndexedNotNull { index, message ->
                            message.toEntityOrNull(conversationId, index)
                        }
                }
                val contextCheckpoints = sorted.map { (conversationId, state) ->
                    ConversationContextCheckpointEntity(
                        conversationId = conversationId,
                        historyJson = encodeCheckpoint(state.history),
                    )
                }
                val dao = EtaDatabase.get(appContext).conversationDao()
                dao.replaceAll(
                    conversations = conversations,
                    messages = messages,
                    contextCheckpoints = contextCheckpoints,
                    state = selected?.let { ConversationStateEntity(selectedConversationId = it) },
                )
                dao.replaceFolders(
                    folders.mapIndexed { index, folder ->
                        ConversationFolderEntity(
                            id = folder.id,
                            name = folder.name,
                            sortIndex = folder.sortIndex.takeIf { it > 0 } ?: index,
                            createdAt = now,
                        )
                    },
                )
            }
        }
    }

    private fun encodeCheckpoint(history: List<AgentModelClient.ConversationMessage>): String =
        try {
            AgentConversationCodec.encodeConversationCheckpoint(history)
        } catch (_: Throwable) {
            AgentConversationCodec.encodeConversationCheckpoint(history.map { it.copy(turnId = "") })
        }

    private suspend fun loadSnapshot(context: Context): Snapshot {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversations = dao.conversations()
        if (conversations.isEmpty()) {
            return Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
                folders = dao.folders().toUiFolders(),
            )
        }

        val messagesByConversation = conversations.associate { conversation ->
            conversation.id to buildList {
                var offset = 0
                while (true) {
                    val page = dao.messagesPage(
                        conversationId = conversation.id,
                        limit = MESSAGE_LOAD_PAGE_SIZE,
                        offset = offset,
                    )
                    addAll(page)
                    if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                    offset += page.size
                }
            }
        }
        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()

        val (fallbackProviderId, fallbackModelId) = defaultSelection()
        conversations.forEach { conversation ->
            val history = AgentConversationCodec.decodeTranscript(
                dao.contextCheckpoint(conversation.id)?.historyJson
            ).ifEmpty {
                messagesByConversation[conversation.id]
                    .orEmpty()
                    .sortedBy { it.sortIndex }
                    .toLegacyHistory()
            }
            val messages = attachUserImageSources(
                messages = messagesByConversation[conversation.id]
                    .orEmpty()
                    .sortedBy { it.sortIndex }
                    .mapNotNull { it.toMessageOrNull() },
                history = history,
            )
            states[conversation.id] = AgentChatHomeUiState(
                messages = messages,
                history = history,
                appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = conversation.reasoningEffortValue.enablesReasoning,
                reasoningEffort = conversation.reasoningEffortValue,
                providerId = if (conversation.providerId.isBlank() && conversation.modelId.isBlank()) fallbackProviderId else conversation.providerId,
                modelId = if (conversation.providerId.isBlank() && conversation.modelId.isBlank()) fallbackModelId else conversation.modelId,
                assistantId = conversation.assistantId,
            )
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
        }

        val selected = dao.state()?.selectedConversationId
            ?.takeIf { it in states }
            ?: states.keys.first()

        return Snapshot(
            selectedConversationId = selected,
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
            folderIds = conversations
                .mapNotNull { conversation ->
                    conversation.folderId.takeIf { it.isNotBlank() }?.let { conversation.id to it }
                }
                .toMap(),
            pinnedIds = conversations.filter { it.isPinned }.map { it.id }.toSet(),
            folders = dao.folders().toUiFolders(),
        )
    }


    private fun List<ConversationFolderEntity>.toUiFolders(): List<ConversationFolderUi> =
        map { folder ->
            ConversationFolderUi(
                id = folder.id,
                name = folder.name,
                sortIndex = folder.sortIndex,
            )
        }

    private val ConversationMetadata.reasoningEffortValue: ReasoningEffort
        get() = ReasoningEffort.fromWireValue(reasoningEffort) ?: ReasoningEffort.DEFAULT

    private suspend fun defaultSelection(): Pair<String, String> {
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        return settings?.selectedProviderId.orEmpty() to settings?.selectedModelId.orEmpty()
    }

    private fun AgentChatMessageUi.toEntityOrNull(
        conversationId: String,
        sortIndex: Int,
    ): ConversationMessageEntity? =
        when (this) {
            is UserMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_USER,
                content = content,
                imagesJson = encodeUserMessageImages(images, imageSources, imageIsVideo, imageDurationsMs),
                isEdited = isEdited,
            )

            is AgentMessageUi -> {
                if (content.isBlank() && isStreaming) {
                    null
                } else {
                    ConversationMessageEntity(
                        id = id,
                        conversationId = conversationId,
                        sortIndex = sortIndex,
                        type = TYPE_ASSISTANT,
                        content = content,
                        renderMarkdown = renderMarkdown,
                        contextTokens = usage?.contextTokens,
                        inputTokens = usage?.inputTokens,
                        outputTokens = usage?.outputTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        cachedTokens = usage?.cachedTokens,
                        generatedAtMillis = generatedAtMillis,
                    )
                }
            }

            is SystemNoticeMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_SYSTEM_NOTICE,
                content = code.wireValue,
                resultSummary = detail,
                renderMarkdown = false,
            )

            is ThinkingMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_THINKING,
                content = content,
                elapsedSeconds = elapsedSeconds,
            )

            is ToolActivityMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL,
                content = command.orEmpty(),
                toolName = toolName,
                toolStatus = status.name,
                argumentsSummary = argumentsSummary,
                resultSummary = resultSummary,
                imageCount = imageCount,
            )

            is ToolSummaryMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL_SUMMARY,
                content = "",
                toolsJson = tools.toJsonArrayString(),
            )

            is ContextCompactedMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_CONTEXT_COMPACTED,
                content = summary,
                elapsedSeconds = compactedCount,
                argumentsSummary = compressorLabel,
                contextTokens = baselineTokens.takeIf { it > 0 },
                inputTokens = preservedUsage.inputTokens.toTokenColumn().takeIf { it > 0 },
                outputTokens = preservedUsage.outputTokens.toTokenColumn(),
                cachedTokens = preservedUsage.cachedTokens.toTokenColumn().takeIf { it > 0 },
                imageCount = resumeRound.coerceAtLeast(0),
            )

            else -> null
        }

    private fun ConversationMessageEntity.toMessageOrNull(): AgentChatMessageUi? =
        when (type) {
            TYPE_USER -> {
                val decoded = decodeUserMessageImages(imagesJson)
                UserMessageUi(
                    id = id,
                    content = content,
                    images = decoded.previews,
                    isEdited = isEdited,
                    imageSources = decoded.sources,
                    imageIsVideo = decoded.videoFlags,
                    imageDurationsMs = decoded.durationsMs,
                )
            }

            TYPE_ASSISTANT -> AgentMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                renderMarkdown = renderMarkdown ?: true,
                generatedAtMillis = generatedAtMillis,
                usage = TokenUsageUi(
                    contextTokens = contextTokens,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                ).takeUnless { it.isEmpty },
            )

            TYPE_SYSTEM_NOTICE -> SystemNoticeCode.fromWireValue(content)?.let { code ->
                SystemNoticeMessageUi(
                    id = id,
                    code = code,
                    detail = resultSummary,
                )
            }

            TYPE_THINKING -> ThinkingMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds,
                collapsed = true,
            )

            TYPE_TOOL -> ToolActivityMessageUi(
                id = id,
                toolName = toolName.orEmpty(),
                status = toolStatus.orEmpty().toToolStatus(),
                argumentsSummary = argumentsSummary.orEmpty(),
                command = content.takeIf(String::isNotBlank),
                resultSummary = resultSummary,
                imageCount = imageCount,
            )

            TYPE_TOOL_SUMMARY -> ToolSummaryMessageUi(
                id = id,
                tools = toolsJson.toStringList(),
            )

            TYPE_CONTEXT_COMPACTED -> {
                val legacyResumeRound = outputTokens == null
                ContextCompactedMessageUi(
                    id = id,
                    compactedCount = elapsedSeconds ?: 0,
                    summary = content,
                    compressorLabel = argumentsSummary.orEmpty(),
                    baselineTokens = contextTokens ?: 0,
                    resumeRound = if (legacyResumeRound) inputTokens ?: 0 else imageCount,
                    preservedUsage = if (legacyResumeRound) {
                        ConversationTokenUsageUi()
                    } else {
                        ConversationTokenUsageUi(
                            inputTokens = (inputTokens ?: 0).toLong(),
                            outputTokens = (outputTokens ?: 0).toLong(),
                            cachedTokens = (cachedTokens ?: 0).toLong(),
                        )
                    },
                )
            }

            else -> null
        }

    private fun String.toToolStatus(): ToolActivityStatusUi =
        runCatching { ToolActivityStatusUi.valueOf(this) }.getOrNull()
            ?.let { status ->
                if (status == ToolActivityStatusUi.Running) ToolActivityStatusUi.Unknown else status
            }
            ?: ToolActivityStatusUi.Unknown

    private fun List<String>.toJsonArrayString(): String =
        JSONArray().also { array ->
            forEach { array.put(it) }
        }.toString()

    private fun String.toStringList(): List<String> =
        runCatching {
            val array = JSONArray(this)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun List<ConversationMessageEntity>.toLegacyHistory(): List<AgentModelClient.ConversationMessage> =
        mapNotNull { message ->
            when (message.type) {
                TYPE_USER -> AgentModelClient.ConversationMessage(
                    role = "user",
                    content = message.content,
                )
                TYPE_ASSISTANT -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { content ->
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = content,
                        )
                    }
                else -> null
            }
        }


    private fun Long.toTokenColumn(): Int =
        coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private const val TYPE_USER = "user"
    private const val TYPE_ASSISTANT = "assistant"
    private const val TYPE_SYSTEM_NOTICE = "system_notice"
    private const val TYPE_THINKING = "thinking"
    private const val TYPE_TOOL = "tool"
    private const val TYPE_TOOL_SUMMARY = "tool_summary"
    private const val TYPE_CONTEXT_COMPACTED = "context_compacted"
    private const val MESSAGE_LOAD_PAGE_SIZE = 128
    private const val LEGACY_UNNAMED_TITLE = "新对话"
}
