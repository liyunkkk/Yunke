package io.github.mangi.eta.data.datastore

import io.github.mangi.eta.data.repository.ConversationUsageTotals
import io.github.mangi.eta.data.repository.ModelUsageDelta
import io.github.mangi.eta.data.repository.MutableModelUsageLedger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A null read means no committed ledger, not an empty committed ledger. */
internal interface UsageLedgerStorage {
    fun read(): String?
    fun write(raw: String)
}

/**
 * The file contains the original ledger JSON, without a lossy schema conversion.
 * A crash before the atomic rename leaves the old file (or legacy Preferences) authoritative;
 * an orphan .pending file is never treated as a completed migration. File data is fsynced.
 */
internal class AtomicUsageLedgerFile(private val file: File) : UsageLedgerStorage {
    override fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    override fun write(raw: String) {
        val parent = checkNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create ledger directory")
        val pending = File(parent, "${file.name}.pending")
        FileOutputStream(pending).use { output ->
            output.write(raw.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        // Do not degrade to delete+rename: an unsupported atomic move must retain legacy data.
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * A single mutex owns migration, the mutable working tree, imports and commits. Partial deltas
 * are applied immediately in memory and in arrival order; commits are bounded to 1 s / 64 edits,
 * with synchronous flushes at request completion, snapshot/export and explicit replacement.
 * No WAL: abrupt process death can lose only the dirty interval when storage is healthy. A
 * failed write retains the dirty tree and retries; no finite durability bound is possible while
 * storage itself is failing. Snapshot/export propagates write failure rather than exporting stale
 * bytes. Settings observers never subscribe to this store.
 */
internal class UsageLedgerStore(
    private val storage: UsageLedgerStorage,
    private val readLegacy: suspend () -> String?,
    private val clearLegacy: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val flushIntervalMillis: Long = 1_000L,
    private val maxDirtyEdits: Int = 64,
) {
    private val mutex = Mutex()
    private val rawState = MutableStateFlow<String?>(null)
    private val totalsState = MutableStateFlow<Map<String, ConversationUsageTotals>?>(null)
    private var loaded = false
    private var migrationPending = false
    private var cleanupPending = true
    private var committed = ""
    private var ledger: MutableModelUsageLedger? = null
    private var dirtyEdits = 0
    private var timer: Job? = null
    private var timerGeneration = 0L

    init {
        require(flushIntervalMillis > 0)
        require(maxDirtyEdits > 0)
    }

    fun rawFlow(): Flow<String> = flow {
        withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(requireDurable = false) } }
        emitAll(rawState.filterNotNull().distinctUntilChanged())
    }

    fun conversationFlow(id: String?): Flow<ConversationUsageTotals?> = flow {
        withContext(Dispatchers.IO) { mutex.withLock { ensureLoaded(requireDurable = false) } }
        emitAll(totalsState.filterNotNull().map { totals ->
            if (id.isNullOrBlank()) ConversationUsageTotals() else totals[id]
        }.distinctUntilChanged())
    }

    suspend fun snapshot(): String = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(requireDurable = false)
            // A read must still work while the initial copy is incomplete. Pending edits, however,
            // must be committed before export; their failure cannot silently return an old ledger.
            flushLocked()
            committed
        }
    }

    suspend fun record(delta: ModelUsageDelta) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(requireDurable = true)
            val working = checkNotNull(ledger)
            if (!working.apply(delta)) return@withLock
            totalsState.value = working.conversationTotalsSnapshot()
            dirtyEdits++
            if (dirtyEdits >= maxDirtyEdits) {
                flushLocked()
            } else {
                scheduleFlush()
            }
        }
    }

    suspend fun flush() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(requireDurable = false)
            flushLocked()
        }
    }

    suspend fun replace(raw: String) = update { raw }

    suspend fun update(transform: (String) -> String) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded(requireDurable = true)
            val current = if (dirtyEdits > 0) checkNotNull(ledger).serialize() else committed
            val replacement = transform(current)
            // Commit first: an exception must leave both the previous durable snapshot and the
            // pending working tree intact. Backup import and legacy seeding share this barrier.
            storage.write(replacement)
            cancelTimer()
            dirtyEdits = 0
            install(replacement)
        }
    }

    private suspend fun ensureLoaded(requireDurable: Boolean) {
        if (!loaded) {
            val persisted = try {
                storage.read()
            } catch (failure: IOException) {
                val legacy = readLegacy() ?: throw failure
                install(legacy)
                // Do not overwrite an unreadable committed file with potentially stale legacy
                // bytes. Keep retrying the read; only a confirmed absent file may be migrated.
                if (requireDurable) throw failure
                return
            }
            if (persisted != null) {
                install(persisted)
            } else {
                install(readLegacy().orEmpty())
                migrationPending = true
            }
            loaded = true
        }
        if (migrationPending) {
            try {
                storage.write(committed)
                migrationPending = false
            } catch (failure: IOException) {
                if (requireDurable) throw failure
                return // The old value remains readable and will be retried on the next access.
            }
        }
        if (cleanupPending) {
            try {
                clearLegacy() // Only after a committed independent file exists.
                cleanupPending = false
            } catch (_: IOException) {
                // Reentrant: a surviving old key cannot overwrite an already committed file.
            }
        }
    }

    private fun install(raw: String) {
        committed = raw
        ledger = MutableModelUsageLedger(raw)
        rawState.value = raw
        totalsState.value = checkNotNull(ledger).conversationTotalsSnapshot()
    }

    private fun flushLocked() {
        if (dirtyEdits == 0) return
        cancelTimer()
        try {
            val raw = checkNotNull(ledger).serialize()
            storage.write(raw)
            committed = raw
            dirtyEdits = 0
            rawState.value = raw
        } finally {
            if (dirtyEdits > 0) scheduleFlush()
        }
    }

    private fun cancelTimer() {
        timerGeneration++ // An expired, cancelled timer may already be waiting for the mutex.
        timer?.cancel()
        timer = null
    }

    private fun scheduleFlush() {
        if (timer != null) return
        val generation = ++timerGeneration
        timer = scope.launch {
            delay(flushIntervalMillis)
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generation != timerGeneration) return@withLock
                    timer = null
                    // A later explicit snapshot/end-of-request flush also reports this failure.
                    // The background retry must not kill the process or discard the dirty ledger.
                    try { flushLocked() } catch (_: Exception) { /* retry scheduled by finally */ }
                }
            }
        }
    }
}
