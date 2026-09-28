package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import io.github.mangi.eta.ui.model.CloudUsageReceiptCodec
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.model.ConversationCheckpointTooLargeException
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationFolderEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationDao
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

    fun load(context: Context, selectedOnly: Boolean = false): Snapshot =
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).withTransaction {
                loadSnapshot(context.applicationContext, selectedOnly)
            }
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

    /**
     * 浮窗与本体同进程但不同状态所有者（Service vs Activity ViewModel）。
     * 浮窗改库后自增它，本体在组合里读这个快照值即可立刻对齐，
     * 不必依赖 ON_RESUME —— 浮窗是 overlay，不改变 Activity 生命周期。
     */
    var externalRevision by mutableStateOf(0)
        internal set

    /** 库里现存会话 id：本体用它和内存快照做差集，判断浮窗是否在外部改过库。 */
    suspend fun conversationIds(context: Context): List<String> =
        withContext(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).conversationDao().conversationIds()
        }

    /** 删除单个会话：只摘掉这一条，不做全量重写，避免与本体内存快照互相覆盖。 */
    suspend fun deleteConversation(context: Context, conversationId: String) {
        val appContext = context.applicationContext
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                EtaDatabase.get(appContext).conversationDao()
                    .deleteConversationCascade(conversationId)
            }
        }
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
                val database = EtaDatabase.get(appContext)
                val dao = database.conversationDao()
                // Keep the existing all-or-nothing snapshot contract, but encode/write one
                // conversation and one message page at a time instead of materializing
                // every entity and every checkpoint JSON before starting the transaction.
                database.withTransaction {
                    val existing = dao.conversations().associateBy { it.id }
                    require(sorted.all { (id, state) -> state.conversationContentLoaded || id in existing }) {
                        "Cannot save a new conversation without loaded content"
                    }
                    existing.keys.filterNot { it in storedIds }.forEach { dao.deleteConversation(it) }
                    val metadata = conversations.map { row ->
                        row.copy(createdAt = existing[row.id]?.createdAt ?: row.createdAt)
                    }
                    dao.insertMissingConversations(metadata)
                    dao.updateConversationMetadata(metadata.map { row ->
                        ConversationMetadata(row.id, row.title, row.thinkingEnabled, row.reasoningEffort,
                            row.appliedRuntimeRunIdsJson, row.createdAt, row.updatedAt, row.folderId,
                            row.isPinned, row.providerId, row.modelId, row.assistantId)
                    })
                    dao.deleteState()
                    for ((conversationId, state) in sorted) {
                        if (!state.conversationContentLoaded) continue
                        dao.deleteMessagesForConversation(conversationId)
                        dao.deleteContextCheckpoint(conversationId)
                        val pages = state.messages.asSequence()
                            .mapIndexedNotNull { index, message -> message.toEntityOrNull(conversationId, index) }
                            .chunked(MESSAGE_LOAD_PAGE_SIZE)
                        for (page in pages) dao.insertMessages(page)
                        val encodedHistory = encodeCheckpoint(state.history)
                        dao.insertContextCheckpoints(listOf(ConversationContextCheckpointEntity(
                            conversationId = conversationId,
                            historyJson = encodedHistory,
                            cloudUsageJson = CloudUsageReceiptCodec.encode(
                                conversationId, state.providerId, state.modelId, encodedHistory,
                                state.livePromptTokens.takeUnless { state.livePromptIsProjected },
                                state.cloudHistoryTokens, state.cloudRequestOverheadTokens,
                            ),
                        )))
                    }
                    selected?.let { dao.insertState(ConversationStateEntity(selectedConversationId = it)) }
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
    }

    fun searchStoredConversation(
        context: Context, id: String, title: String, updatedAt: Long, query: String,
        unnamedTitle: String, roles: io.github.mangi.eta.ui.model.MessageSearchRoleLabels,
    ): List<io.github.mangi.eta.ui.model.MessageSearchHit> = runBlocking(Dispatchers.IO) {
        if (query.isBlank()) return@runBlocking emptyList()
        val database = EtaDatabase.get(context.applicationContext)
        database.withTransaction {
        val dao = database.conversationDao()
        buildList {
            var offset = 0
            while (true) {
                val page = dao.searchablePage(id, MESSAGE_LOAD_PAGE_SIZE, offset)
                val state = AgentChatHomeUiState(messages = page.mapNotNull { it.asMessageEntity().toMessageOrNull() },
                    input = "", isStreaming = false, thinkingEnabled = false)
                addAll(io.github.mangi.eta.ui.model.searchConversationMessages(
                    mapOf(id to state), mapOf(id to title), mapOf(id to updatedAt), query, unnamedTitle, roles))
                if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                offset += page.size
            }
        }
        }
    }

    fun unloadedPreview(state: AgentChatHomeUiState): AgentChatHomeUiState {
        val preview = when (val last = state.messages.lastOrNull()) {
            is UserMessageUi -> last.copy(content = last.content.take(2048), images = emptyList(),
                imageSources = emptyList(), imageIsVideo = emptyList(), imageDurationsMs = emptyList())
            is AgentMessageUi -> last.copy(content = last.content.take(2048))
            is ThinkingMessageUi -> last.copy(content = "")
            is ToolActivityMessageUi -> last.copy(command = null, resultSummary = null, argumentsSummary = "")
            is SystemNoticeMessageUi -> last.copy(detail = null)
            else -> null
        }
        return state.copy(messages = listOfNotNull(preview), history = emptyList(), conversationContentLoaded = false,
            childContexts = emptyList(), childContextRunId = "", selectedContextTaskId = null)
    }

    /**
     * 只有"受保护回合本身超过容量上限"才允许放弃保护重编：那时保留保护只会让整轮写不进去。
     * OOM、序列化故障等其它异常必须上抛——过去一律 catch 会把它们也当成容量问题，
     * 于是清掉全部 turnId 再编一次，保护静默失效、前缀消息被悄悄丢掉。
     */
    private fun encodeCheckpoint(history: List<AgentModelClient.ConversationMessage>): String =
        try {
            AgentConversationCodec.encodeConversationCheckpoint(history)
        } catch (tooLarge: ConversationCheckpointTooLargeException) {
            AndroidAgentLogger.warn(
                "Conversation checkpoint exceeded its cap with ${history.size} messages; " +
                    "retrying without turn protection"
            )
            // Do not retry OOM by allocating another full list: only the capacity case lands here.
            AgentConversationCodec.encodeConversationCheckpoint(history.map { it.copy(turnId = "") })
        }

    fun loadConversation(context: Context, id: String): AgentChatHomeUiState? = runBlocking(Dispatchers.IO) {
        val database = EtaDatabase.get(context.applicationContext)
        database.withTransaction {
            val dao = database.conversationDao()
            dao.conversationMetadata(id)?.let { loadConversationState(dao, it, true) }
        }
    }

    private suspend fun loadConversationState(
        dao: ConversationDao, conversation: ConversationMetadata, withContent: Boolean,
    ): AgentChatHomeUiState {
        val (fallbackProviderId, fallbackModelId) = defaultSelection()
        val checkpoint = if (withContent) dao.contextCheckpoint(conversation.id) else null
        val decodedHistory = if (withContent) AgentConversationCodec.decodeTranscript(checkpoint?.historyJson) else emptyList()
        val legacyHistory = mutableListOf<AgentModelClient.ConversationMessage>()
        val uiMessages = buildList {
            if (!withContent) {
                dao.conversationPreview(conversation.id)?.asMessageEntity()?.toMessageOrNull()?.let { add(it) }
            } else {
                var offset = 0
                while (true) {
                    val page = dao.messagesPage(conversation.id, MESSAGE_LOAD_PAGE_SIZE, offset)
                    // Never retain the raw rows for the whole conversation alongside UI objects.
                    page.mapNotNullTo(this) { it.toMessageOrNull() }
                    if (decodedHistory.isEmpty()) legacyHistory.addAll(page.toLegacyHistory())
                    if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                    offset += page.size
                }
            }
        }
        val history = decodedHistory.ifEmpty { legacyHistory }
        val messages = attachUserImageSources(messages = uiMessages, history = history)
        // Invalid receipts must not resurrect bills from a different history/model.
        val receipt = checkpoint?.takeIf { decodedHistory.isNotEmpty() }?.let {
            CloudUsageReceiptCodec.decodeReceipt(it.cloudUsageJson, conversation.id,
                conversation.providerId, conversation.modelId, it.historyJson)
        }
        return AgentChatHomeUiState(
            conversationContentLoaded = withContent,
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
            livePromptTokens = receipt?.inputTokens,
            cloudHistoryTokens = receipt?.historyTokens,
            cloudRequestOverheadTokens = receipt?.overheadTokens,
        )
    }

    private suspend fun loadSnapshot(context: Context, selectedOnly: Boolean): Snapshot {
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

        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()

        val selected = dao.state()?.selectedConversationId
            ?.takeIf { id -> conversations.any { it.id == id } }
            ?: conversations.first().id
        conversations.forEach { conversation ->
            states[conversation.id] = loadConversationState(dao, conversation, !selectedOnly || conversation.id == selected)
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
        }

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
