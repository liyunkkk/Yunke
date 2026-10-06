package io.github.mangi.eta.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.mangi.eta.data.repository.ModelUsageDelta
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Transactional fault injection: run the real edit transform, fail BEFORE committing any keys. */
internal class FaultPreferencesStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val mutex = Mutex()
    val committed = MutableStateFlow(initial)
    var attempts = 0
    var reads = 0
    var failures = 0
    var beforeCommit: suspend () -> Unit = {}
    override val data: Flow<Preferences> = flow { reads++; emitAll(committed) }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
        attempts++
        val updated = transform(committed.value)
        beforeCommit()
        if (failures > 0) { failures--; throw IOException("Injected Preferences commit failure") }
        committed.value = updated
        updated
    }
}

internal fun usageDelta(request: String = "request", input: Long = 100) = ModelUsageDelta(
    "provider", "Provider", "model", "Model", input, 10, 30, 5,
    conversationId = "owner", requestId = request, atMillis = 1000,
)

/** Test-only wiring of the REAL singleton API to a private/injected Preferences store. */
internal suspend fun <T> withSettingsStore(store: DataStore<Preferences>, block: suspend () -> T): T {
    val data = SettingsDataStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
    val ledger = SettingsDataStore::class.java.getDeclaredField("usageLedger").apply { isAccessible = true }
    val oldData = data.get(SettingsDataStore)
    val oldLedger = ledger.get(SettingsDataStore)
    data.set(SettingsDataStore, store)
    ledger.set(SettingsDataStore, PreferencesUsageLedger(store))
    try { return block() }
    finally { data.set(SettingsDataStore, oldData); ledger.set(SettingsDataStore, oldLedger) }
}

/** Anonymous user-shaped fixture: 125 lifetime conversation totals, 12 providers, ~4.1 MB. */
internal fun largeUsageLedger(): String {
    val totals = JSONObject()
    repeat(125) { index -> totals.put("owner-$index", JSONObject()
        .put("in", 9007199254740993L + index).put("out", 11L + index).put("k", 7L).put("w", 3L)) }
    val providers = JSONObject()
    repeat(12) { provider ->
        val events = JSONArray()
        repeat(3000) { index -> events.put(JSONObject().put("t", 1000L + index).put("in", 42L)
            .put("out", 2L).put("k", 7L).put("w", 3L).put("c", "owner-${index % 125}")
            .put("q", "request-$provider-$index")) }
        val model = JSONObject().put("inputTokens", 9007199254740993L).put("outputTokens", 6000L)
            .put("cachedTokens", 21000L).put("cacheCreationTokens", 9000L).put("events", events)
            .put("futureDecimal", 1.25e-7)
        providers.put("provider-$provider", JSONObject().put("name", "Provider $provider")
            .put("models", JSONObject().put("model", model)))
    }
    val root = JSONObject().put("conversationTotalsInitialized", true)
        .put("conversationTotalsV1", totals).put("providers", providers)
    val initialSize = root.toString().length
    providers.getJSONObject("provider-0").getJSONObject("models").getJSONObject("model")
        .put("futureExtension", "x".repeat((4_100_000 - initialSize).coerceAtLeast(0)))
    return "  ${root}\n" // Formatting must survive migration too, not just semantic counters.
}
