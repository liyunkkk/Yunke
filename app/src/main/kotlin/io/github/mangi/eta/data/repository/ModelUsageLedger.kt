package io.github.mangi.eta.data.repository

import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

internal data class ModelUsageSnapshot(
    val providers: List<ModelUsageProviderUi> = emptyList(),
) {
    val totalInputTokens: Long get() = providers.sumOf { it.inputTokens }
    val totalFreshInputTokens: Long get() = providers.sumOf { it.freshInputTokens }
    val totalOutputTokens: Long get() = providers.sumOf { it.outputTokens }
    val totalCachedTokens: Long get() = providers.sumOf { it.cachedTokens }
    val totalCacheCreationTokens: Long get() = providers.sumOf { it.cacheCreationTokens }
    val totalTokens: Long get() = totalInputTokens + totalOutputTokens

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageSnapshot {
        if (startMillis == null && endMillis == null) return this
        return ModelUsageSnapshot(
            providers = providers.mapNotNull { provider ->
                val models = provider.models.mapNotNull { it.filtered(startMillis, endMillis) }
                if (models.isEmpty()) null else provider.copy(models = models)
            },
        )
    }
}

internal data class ModelUsageProviderUi(
    val id: String,
    val name: String,
    val models: List<ModelUsageModelUi>,
) {
    val inputTokens: Long get() = models.sumOf { it.inputTokens }
    val freshInputTokens: Long get() = models.sumOf { it.freshInputTokens }
    val outputTokens: Long get() = models.sumOf { it.outputTokens }
    val cachedTokens: Long get() = models.sumOf { it.cachedTokens }
    val cacheCreationTokens: Long get() = models.sumOf { it.cacheCreationTokens }
}

internal data class ModelUsageEvent(
    val atMillis: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long = 0L,
    val cacheCreationTokens: Long = 0L,
    val conversationId: String? = null,
    val round: Int? = null,
    val requestId: String? = null,
)

internal data class ModelUsageModelUi(
    val id: String,
    val displayName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val conversationCount: Int,
    val activeDays: Int,
    val events: List<ModelUsageEvent> = emptyList(),
    val cachedTokens: Long = 0L,
    val cacheCreationTokens: Long = 0L,
) {
    /** Uncached prefix: full prompt minus reads and writes. */
    val freshInputTokens: Long
        get() = (inputTokens - cachedTokens - cacheCreationTokens).coerceAtLeast(0L)
    /** Fresh input, output, cache reads and cache writes. */
    val totalTokens: Long
        get() = inputTokens + outputTokens
    val dailyAverageTokens: Long
        get() = if (activeDays <= 0) 0L else freshInputTokens / activeDays
    val conversationAverageTokens: Long
        get() = if (conversationCount <= 0) 0L else freshInputTokens / conversationCount

    fun filtered(startMillis: Long?, endMillis: Long?): ModelUsageModelUi? {
        if (startMillis == null && endMillis == null) return this
        val matched = events.filter { event ->
            (startMillis == null || event.atMillis >= startMillis) &&
                (endMillis == null || event.atMillis <= endMillis)
        }.collapsedByRound()
        if (matched.isEmpty()) return null
        val conversations = matched.mapNotNull { it.conversationId }.toSet()
        val days = matched.map { eventDay(it.atMillis) }.toSet()
        val filteredInput = matched.sumOf { it.inputTokens }
        return copy(
            inputTokens = filteredInput,
            outputTokens = matched.sumOf { it.outputTokens },
            cachedTokens = matched.sumOf { it.cachedTokens },
            cacheCreationTokens = matched.sumOf { it.cacheCreationTokens },
            conversationCount = conversations.size,
            activeDays = days.size,
            events = matched,
        )
    }
}

internal data class ModelUsageDelta(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelDisplayName: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long = 0L,
    val cacheCreationTokens: Long = 0L,
    val conversationId: String? = null,
    val round: Int? = null,
    val requestId: String? = null,
    val atMillis: Long = System.currentTimeMillis(),
    val day: LocalDate = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
)

internal fun decodeModelUsageSnapshot(raw: String?): ModelUsageSnapshot {
    if (raw.isNullOrBlank()) return ModelUsageSnapshot()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return ModelUsageSnapshot()
    val providersJson = root.optJSONObject("providers") ?: return ModelUsageSnapshot()
    val providers = buildList {
        providersJson.keys().forEach { providerId ->
            val provider = providersJson.optJSONObject(providerId) ?: return@forEach
            val modelsJson = provider.optJSONObject("models") ?: JSONObject()
            val models = buildList {
                modelsJson.keys().forEach { modelId ->
                    val model = modelsJson.optJSONObject(modelId) ?: return@forEach
                    val events = decodeEvents(model.optJSONArray("events"))
                    val conversations = stringSet(model.optJSONArray("conversations"))
                    val days = stringSet(model.optJSONArray("days"))
                    val input = if (events.isNotEmpty()) {
                        maxOf(model.optLong("inputTokens"), events.sumOf { it.inputTokens })
                    } else {
                        model.optLong("inputTokens")
                    }
                    val output = if (events.isNotEmpty()) {
                        maxOf(model.optLong("outputTokens"), events.sumOf { it.outputTokens })
                    } else {
                        model.optLong("outputTokens")
                    }
                    val cached = if (events.isNotEmpty()) {
                        maxOf(model.optLong("cachedTokens"), events.sumOf { it.cachedTokens })
                    } else {
                        model.optLong("cachedTokens")
                    }
                    val created = if (events.isNotEmpty()) {
                        maxOf(model.optLong("cacheCreationTokens"), events.sumOf { it.cacheCreationTokens })
                    } else {
                        model.optLong("cacheCreationTokens")
                    }
                    add(
                        ModelUsageModelUi(
                            id = modelId,
                            displayName = model.optString("displayName").ifBlank { modelId },
                            inputTokens = input,
                            outputTokens = output,
                            conversationCount = if (events.isNotEmpty()) {
                                (conversations + events.mapNotNull { it.conversationId }).size
                            } else {
                                conversations.size
                            },
                            activeDays = when {
                                days.isNotEmpty() -> days.size
                                events.isNotEmpty() -> events.map { eventDay(it.atMillis) }.toSet().size
                                else -> 0
                            },
                            events = events,
                            cachedTokens = cached,
                            cacheCreationTokens = created,
                        ),
                    )
                }
            }.sortedWith(
                compareByDescending<ModelUsageModelUi> { it.inputTokens }
                    .thenBy { it.displayName.lowercase() },
            )
            if (models.isNotEmpty()) {
                add(
                    ModelUsageProviderUi(
                        id = providerId,
                        name = provider.optString("name").ifBlank { providerId },
                        models = models,
                    ),
                )
            }
        }
    }.sortedBy { it.name.lowercase() }
    return ModelUsageSnapshot(providers = providers)
}

internal fun applyModelUsageDelta(raw: String?, delta: ModelUsageDelta): String {
    if (delta.providerId.isBlank() || delta.modelId.isBlank()) return raw.orEmpty()
    if (delta.inputTokens <= 0L && delta.outputTokens <= 0L && delta.conversationId.isNullOrBlank()) {
        return raw.orEmpty()
    }
    val root = runCatching {
        val parsed = JSONObject(raw?.takeIf { it.isNotBlank() } ?: "{}")
        val initialized = parsed.optBoolean("conversationTotalsInitialized")
        seedConversationUsageInPlace(parsed, raw, emptyMap())
        // Migration retains the original round-trip. Initialized ledgers can reuse the root
        // only when scalar reads and the updated model's string coercions are round-trip safe.
        parsed.rootForModelUsageDelta(delta.providerId, delta.modelId, initialized)
    }.getOrDefault(JSONObject())
    val providers = root.optJSONObject("providers") ?: JSONObject().also {
        root.put("providers", it)
    }
    val provider = providers.optJSONObject(delta.providerId) ?: JSONObject().also {
        providers.put(delta.providerId, it)
    }
    provider.put("name", delta.providerName.ifBlank { delta.providerId })
    val models = provider.optJSONObject("models") ?: JSONObject().also {
        provider.put("models", it)
    }
    val model = models.optJSONObject(delta.modelId) ?: JSONObject().also {
        models.put(delta.modelId, it)
    }
    model.put("displayName", delta.modelDisplayName.ifBlank { delta.modelId })
    val events = decodeEvents(model.optJSONArray("events")).toMutableList()
    // Preserve cumulative counters when the detail window is trimmed; initialize old event-only data.
    for (field in listOf("inputTokens", "outputTokens", "cachedTokens", "cacheCreationTokens")) {
        if (!model.has(field)) model.put(field, events.sumOf {
            when (field) {
                "inputTokens" -> it.inputTokens
                "outputTokens" -> it.outputTokens
                "cachedTokens" -> it.cachedTokens
                else -> it.cacheCreationTokens
            }
        })
    }
    val incoming = ModelUsageEvent(
        atMillis = delta.atMillis,
        inputTokens = delta.inputTokens.coerceAtLeast(0L),
        outputTokens = delta.outputTokens.coerceAtLeast(0L),
        cachedTokens = delta.cachedTokens.coerceAtLeast(0L),
        cacheCreationTokens = delta.cacheCreationTokens.coerceAtLeast(0L),
        conversationId = delta.conversationId,
        round = delta.round,
        requestId = delta.requestId,
    )
    val replaceAt = events.indexOfLast { event ->
        if (!incoming.requestId.isNullOrBlank()) event.requestId == incoming.requestId
        else event.requestId == null && incoming.round != null &&
            !incoming.conversationId.isNullOrBlank() && event.round == incoming.round &&
            event.conversationId == incoming.conversationId
    }
    updateConversationUsage(root, incoming, events.getOrNull(replaceAt))
    if (replaceAt >= 0) {
        val previous = events[replaceAt]
        model.put("inputTokens", model.optLong("inputTokens") - previous.inputTokens + incoming.inputTokens)
        model.put("outputTokens", model.optLong("outputTokens") - previous.outputTokens + incoming.outputTokens)
        model.put("cachedTokens", model.optLong("cachedTokens") - previous.cachedTokens + incoming.cachedTokens)
        model.put("cacheCreationTokens", model.optLong("cacheCreationTokens") - previous.cacheCreationTokens + incoming.cacheCreationTokens)
        events[replaceAt] = incoming
    } else {
        model.put("inputTokens", model.optLong("inputTokens") + incoming.inputTokens)
        model.put("outputTokens", model.optLong("outputTokens") + incoming.outputTokens)
        model.put("cachedTokens", model.optLong("cachedTokens") + incoming.cachedTokens)
        model.put("cacheCreationTokens", model.optLong("cacheCreationTokens") + incoming.cacheCreationTokens)
        events += incoming
    }
    val conversations = stringSet(model.optJSONArray("conversations")).toMutableSet()
    delta.conversationId?.takeIf { it.isNotBlank() }?.let(conversations::add)
    model.put("conversations", JSONArray(conversations.sorted()))
    val days = stringSet(model.optJSONArray("days")).toMutableSet()
    days += delta.day.toString()
    model.put("days", JSONArray(days.sorted()))
    val trimmed = if (events.size > MAX_MODEL_EVENTS) {
        events.takeLast(MAX_MODEL_EVENTS)
    } else {
        events
    }
    model.put("events", StreamPerformanceDiagnostics.measure("usage.ledger.encodeEvents", trimmed.size.toLong()) {
        encodeEvents(trimmed)
    })
    return StreamPerformanceDiagnostics.measure("usage.ledger.serialize") { root.toString() }
}

private fun decodeEvents(array: JSONArray?): List<ModelUsageEvent> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val at = item.optLong("t")
            if (at <= 0L) continue
            add(
                ModelUsageEvent(
                    atMillis = at,
                    inputTokens = item.optLong("in"),
                    outputTokens = item.optLong("out"),
                    cachedTokens = item.optLong("k"),
                    cacheCreationTokens = item.optLong("w"),
                    conversationId = item.optString("c").takeIf { it.isNotBlank() },
                    round = if (item.has("r")) item.optInt("r") else null,
                    requestId = item.optString("q").takeIf { it.isNotBlank() },
                ),
            )
        }
    }
}

private fun encodeEvents(events: List<ModelUsageEvent>): JSONArray =
    JSONArray().also { array ->
        events.forEach { event ->
            array.put(
                JSONObject().also { json ->
                    json.put("t", event.atMillis)
                    json.put("in", event.inputTokens)
                    json.put("out", event.outputTokens)
                    if (event.cachedTokens > 0L) json.put("k", event.cachedTokens)
                    if (event.cacheCreationTokens > 0L) json.put("w", event.cacheCreationTokens)
                    json.put("c", event.conversationId.orEmpty())
                    event.round?.let { json.put("r", it) }
                    event.requestId?.let { json.put("q", it) }
                },
            )
        }
    }

private fun stringSet(array: JSONArray?): Set<String> {
    if (array == null) return emptySet()
    return buildSet {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun eventDay(atMillis: Long): LocalDate =
    Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private const val MAX_MODEL_EVENTS = 4000

internal fun List<ModelUsageEvent>.collapsedByRound(): List<ModelUsageEvent> {
    if (isEmpty()) return this
    val kept = ArrayList<ModelUsageEvent>(size)
    val indexByKey = HashMap<String, Int>()
    forEach { event ->
        val round = event.round
        val conversation = event.conversationId
        if (event.requestId.isNullOrBlank() && (round == null || conversation.isNullOrBlank())) {
            kept += event
        } else {
            val key = event.requestId?.let { "request:$it" } ?: "legacy:$conversation#$round"
            val existing = indexByKey[key]
            if (existing == null) {
                indexByKey[key] = kept.size
                kept += event
            } else {
                kept[existing] = event
            }
        }
    }
    return kept
}

/**
 * Select the actual update root, retaining the old round-trip for migration or unsafe reads.
 * Container values can have canonical scalars yet stringify differently after re-parsing:
 * org.json's object key iteration order need not survive rebuilding its backing map.
 */
internal fun JSONObject.rootForModelUsageDelta(
    providerId: String,
    modelId: String,
    wasInitialized: Boolean,
): JSONObject =
    if (wasInitialized && hasOnlyCanonicalJsonValues() &&
        hasOnlyScalarModelUsageStrings(providerId, modelId)
    ) this else JSONObject(toString())

private fun JSONObject.hasOnlyScalarModelUsageStrings(providerId: String, modelId: String): Boolean {
    val model = optJSONObject("providers")?.optJSONObject(providerId)
        ?.optJSONObject("models")?.optJSONObject(modelId) ?: return true
    // Only this model is decoded and re-encoded during an initialized delta. Other models'
    // containers remain JSON values, so their key order is not materialized into a string.
    val events = model.optJSONArray("events")
    if (events != null) {
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            if (!event.opt("q").isScalarStringCoercionValue() ||
                !event.opt("c").isScalarStringCoercionValue()
            ) return false
        }
    }
    return model.optJSONArray("conversations").hasOnlyScalarStringElements() &&
        model.optJSONArray("days").hasOnlyScalarStringElements()
}

private fun JSONArray?.hasOnlyScalarStringElements(): Boolean {
    if (this == null) return true
    for (index in 0 until length()) {
        if (!opt(index).isScalarStringCoercionValue()) return false
    }
    return true
}

private fun Any?.isScalarStringCoercionValue(): Boolean = when (this) {
    // Missing event fields are safe; raw Java null array entries are rejected by the tree check.
    null, JSONObject.NULL, is String, is Boolean, is Int, is Long -> true
    else -> false
}

/**
 * Checks scalar types recursively, not container identity, object key order or string coercions.
 * Strings, booleans, JSONObject.NULL, Integer and Long keep their scalar read semantics through
 * org.json's round-trip. Any other Number (Double, BigDecimal, BigInteger...) may be rewritten,
 * and a raw Java null (Android's lenient array elision, e.g. `[,"a"]`) becomes JSONObject.NULL,
 * which optString reads differently. Containers recurse here; coercion shapes need a separate check.
 */
internal fun JSONObject.hasOnlyCanonicalJsonValues(): Boolean {
    val keys = keys()
    while (keys.hasNext()) {
        if (!opt(keys.next()).isCanonicalJsonValue()) return false
    }
    return true
}

private fun Any?.isCanonicalJsonValue(): Boolean = when (this) {
    null -> false
    JSONObject.NULL, is String, is Boolean, is Int, is Long -> true
    is JSONObject -> this.hasOnlyCanonicalJsonValues()
    is JSONArray -> (0 until this.length()).all { index -> this.opt(index).isCanonicalJsonValue() }
    else -> false
}
