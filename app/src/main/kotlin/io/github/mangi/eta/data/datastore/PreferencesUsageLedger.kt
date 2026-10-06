package io.github.mangi.eta.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.github.mangi.eta.data.repository.ConversationUsageTotals
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.applyModelUsageDelta
import io.github.mangi.eta.data.repository.decodeConversationUsageTotals
import io.github.mangi.eta.data.repository.validateModelUsageJson
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** One durable authority, no dirty interval/timer or speculative mutable ledger. */
internal class PreferencesUsageLedger(
    private val store: DataStore<Preferences>,
    private val edit: suspend ((MutablePreferences) -> Unit) -> Unit = { transform ->
        store.edit { transform(it) }
        Unit
    },
) {
    // Bounded projection shared by foreground collectors; no JSON decode on their UI dispatcher.
    // Keep only the last raw + immutable totals (not a second mutable copy of the model history).
    private var projectedRaw: String? = null
    private var projectedTotals: Map<String, ConversationUsageTotals> = emptyMap()

    @Synchronized
    private fun project(raw: String): Map<String, ConversationUsageTotals> {
        if (raw != projectedRaw) {
            val root = validateModelUsageJson(raw)
            val totals = decodeConversationUsageTotals(root)
            projectedTotals = totals
            projectedRaw = raw // Install only after validation/decoding succeeds.
        }
        return projectedTotals
    }

    fun rawFlow(): Flow<String> = store.data
        .map { it[MODEL_USAGE_JSON].orEmpty() }
        .distinctUntilChanged()
        .map { raw -> project(raw); raw }
        .flowOn(Dispatchers.IO)

    fun conversationFlow(id: String?): Flow<ConversationUsageTotals?> = rawFlow()
        .map { raw -> if (id.isNullOrBlank()) ConversationUsageTotals() else project(raw)[id] }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    suspend fun preferencesSnapshot(): Preferences = withContext(Dispatchers.IO) {
        store.data.first().also { project(it[MODEL_USAGE_JSON].orEmpty()) }
    }

    suspend fun snapshot(): String = preferencesSnapshot()[MODEL_USAGE_JSON].orEmpty()

    suspend fun record(delta: ModelUsageDelta) = update { raw ->
        StreamPerformanceDiagnostics.measure("usage.ledger.update") { applyModelUsageDelta(raw, delta) }
    }

    suspend fun update(transform: (String) -> String) = withContext(NonCancellable + Dispatchers.IO) {
        edit { prefs ->
            val current = prefs[MODEL_USAGE_JSON].orEmpty()
            project(current) // Never let the legacy lenient updater replace damaged data with {}.
            replaceIn(prefs, transform(current))
        }
    }

    suspend fun replace(raw: String) = update { raw }

    /** Used INSIDE restoreBackup's settings edit; receipt is deliberately not part of backups. */
    fun replaceIn(prefs: MutablePreferences, raw: String) {
        validateModelUsageJson(raw)
        prefs[MODEL_USAGE_JSON] = raw
        // Preserve any migration digest: it identifies the retired SOURCE, not the current stats.
        // Also seal an explicit restore/reset if called with an otherwise unmarked Preferences.
        if (prefs[USAGE_ROLLBACK_RECEIPT] == null) prefs[USAGE_ROLLBACK_RECEIPT] = USAGE_NO_SOURCE_RECEIPT
    }
}
