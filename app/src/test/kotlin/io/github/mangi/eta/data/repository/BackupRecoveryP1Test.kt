package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.config.Prefs
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackupRecoveryP1Test {
    private val app get() = RuntimeEnvironment.getApplication()

    /** A false commit can still change SharedPreferences memory. Never treat memory as durable. */
    private class FalseCommit(private val real: SharedPreferences) : SharedPreferences by real {
        var failures = 0
        override fun edit(): SharedPreferences.Editor {
            val delegate = real.edit()
            return object : SharedPreferences.Editor by delegate {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    delegate.putString(key, value); return this
                }
                override fun remove(key: String?): SharedPreferences.Editor {
                    delegate.remove(key); return this
                }
                override fun clear(): SharedPreferences.Editor {
                    delegate.clear(); return this
                }
                override fun commit(): Boolean {
                    delegate.commit()
                    if (failures > 0) { failures--; return false }
                    return true
                }
            }
        }
    }

    private inline fun withPrefs(block: (FalseCommit) -> Unit) {
        val field = Prefs::class.java.getDeclaredField("localAgent").apply { isAccessible = true }
        val old = field.get(Prefs)
        val real = app.getSharedPreferences("backup-p1-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val wrapped = FalseCommit(real)
        field.set(Prefs, wrapped)
        try { block(wrapped) } finally { field.set(Prefs, old) }
    }

    @Test fun restorePreferencesFalseCommitThrowsEvenWhenMemoryWasUpdated() = withPrefs { prefs ->
        prefs.failures = 1
        assertThrows(IllegalStateException::class.java) {
            Prefs.restoreAgentPreferences(mapOf("example" to "s:value"))
        }
        assertEquals("value", prefs.getString("example", null))
        Prefs.restoreAgentPreferences(emptyMap())
        assertFalse(prefs.contains("example"))
    }

    @Test fun failedOwnerRemovalNeverUsesMissingInMemoryMarkerAsProofOfCompletion() = withPrefs { prefs ->
        val operation = File(app.filesDir, "owner-p1-${UUID.randomUUID()}")
        val id = "conv-${UUID.randomUUID()}"
        try {
            durableText(File(operation, "new-conversation-id"), id)
            val owner = BackupConversationOwnerImport.plan(operation, id, "archive")
            owner.begin(app)
            prefs.failures = 2
            assertThrows(IllegalStateException::class.java) { owner.finish(app, committed = false) }
            // Both keys vanished in memory, but the durable intent must still drive another commit.
            assertEquals(0, prefs.all.size)
            prefs.failures = 1
            BackupConversationOwnerImport.recover(app, operation, committed = false)
            assertEquals(0, prefs.failures)
            assertEquals(0, prefs.all.size)
        } finally {
            operation.deleteRecursively()
        }
    }

    @Test fun brokenStartupJournalKeepsExecutionAdmissionFenced() {
        val operation = File(app.filesDir, "backup-restore")
        operation.mkdirs()
        File(operation, "journal.json").writeText("invalid")
        try {
            assertThrows(Exception::class.java) {
                runBlocking { EtaBackupRepository.recoverInterruptedImport(app) }
            }
            assertTrue(AgentExecutionService.backupMaintenance)
            // A second call still cannot declare success based solely on the existing journal.
            assertThrows(Exception::class.java) {
                runBlocking { EtaBackupRepository.recoverInterruptedImport(app, maintenanceAlreadyHeld = true) }
            }
            assertTrue(AgentExecutionService.backupMaintenance)
        } finally {
            operation.deleteRecursively()
            AgentExecutionService.endBackupMaintenance()
        }
    }
}
