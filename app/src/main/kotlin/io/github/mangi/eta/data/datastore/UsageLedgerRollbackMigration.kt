package io.github.mangi.eta.data.datastore

import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.toMutablePreferences
import io.github.mangi.eta.data.repository.validateModelUsageJson
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal val MODEL_USAGE_JSON = stringPreferencesKey("model_usage_json")
internal val USAGE_ROLLBACK_RECEIPT = stringPreferencesKey("usage_ledger_preferences_receipt_v1")
internal const val USAGE_NO_SOURCE_RECEIPT = "v1:no-source"

internal fun usageLedgerDigest(raw: String): String = MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * DataStore runs this before ANY read/edit and persists ledger + receipt in ONE transaction.
 * The independent, committed file is authoritative (never its orphan .pending sibling).
 * No decoding/re-encoding of the copy: unknown fields, Long precision and formatting survive.
 * A failed read/validation/commit fails initialization; it must not emit empty statistics.
 * cleanUp runs only AFTER DataStore's durable commit. Archival is best effort, not a condition
 * for reading committed Preferences. The receipt fences even a surviving, corrupt/stale source.
 */
internal class UsageLedgerRollbackMigration(
    private val source: File,
    private val archive: (File, String) -> Unit = { file, digest ->
        Files.move(file.toPath(), File(file.parentFile, "${file.name}.preferences-imported.$digest").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
    private val reportArchiveFailure: (Throwable) -> Unit = { failure ->
        // No payload, identifiers, paths, or exception messages in accounting logs.
        try { Log.e("UsageAccounting", "Ledger migration archive failed (${failure.javaClass.simpleName})") }
        catch (_: Exception) { io.github.mangi.eta.data.repository.UsageStatsRepository.reportAccountingFailure(failure) }
        Unit
    },
) : DataMigration<Preferences> {
    private var cleanupDigest: String? = null

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[USAGE_ROLLBACK_RECEIPT] == null || source.exists()

    override suspend fun migrate(currentData: Preferences): Preferences {
        cleanupDigest = null
        val receipt = currentData[USAGE_ROLLBACK_RECEIPT]
        if (receipt != null) {
            // Do NOT compare current ledger with the old digest: new recording/import/reset
            // legitimately changed it. Receipt means Preferences are now the only authority.
            if (receipt == USAGE_NO_SOURCE_RECEIPT) return currentData
            val digest = receipt.removePrefix("v1:sha256:")
            if (!receipt.startsWith("v1:sha256:") || !digest.matches(Regex("[0-9a-f]{64}"))) {
                throw IOException("Invalid usage migration receipt")
            }
            cleanupDigest = digest
            return currentData
        }
        val migrated = currentData.toMutablePreferences()
        if (source.exists()) {
            val raw = readSource()
            // The previous file implementation wrote "" for a never-used ledger. Accept it
            // only when no Preferences statistics would be erased by that ambiguous source.
            if (raw.isBlank() && !currentData[MODEL_USAGE_JSON].isNullOrBlank()) {
                throw IOException("Empty usage source conflicts with existing statistics")
            }
            validateModelUsageJson(raw)
            val digest = usageLedgerDigest(raw)
            migrated[MODEL_USAGE_JSON] = raw
            migrated[USAGE_ROLLBACK_RECEIPT] = "v1:sha256:$digest"
            cleanupDigest = digest
        } else {
            validateModelUsageJson(currentData[MODEL_USAGE_JSON])
            migrated[USAGE_ROLLBACK_RECEIPT] = USAGE_NO_SOURCE_RECEIPT
        }
        return migrated
    }

    override suspend fun cleanUp() {
        val digest = cleanupDigest ?: return
        try {
            if (!source.exists()) return
            // A changed source must never be archived as if it were the confirmed copy.
            if (usageLedgerDigest(readSource()) != digest) {
                throw IOException("Usage source changed before archival")
            }
            archive(source, digest)
        } catch (failure: Exception) {
            // Throwing here would make DataStore retry migration forever despite a good commit.
            // Leave the source and receipt intact; next startup can retry this cleanup only.
            try { reportArchiveFailure(failure) } catch (_: Exception) {
                io.github.mangi.eta.data.repository.UsageStatsRepository.reportAccountingFailure(failure)
            }
        }
    }

    private fun readSource(): String {
        // readText replaces malformed UTF-8. Do not turn damaged user data into a valid copy.
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(source.readBytes())).toString()
    }
}
