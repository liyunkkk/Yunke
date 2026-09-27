package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentWorkspaceOwnershipStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "session-owner-a"
    private val other = "session-owner-b"
    private val project = "/workspace/Project"
    private val id = "a".repeat(32)
    private val nextId = "b".repeat(32)
    private val ledger get() = File(temporary.root, "agent-workspace-ownership.json")
    private fun store() = AgentWorkspaceOwnershipStore(temporary.root)
    private fun ids(ownerId: String = owner) = store().ids(ownerId, "debian", project)

    @Test
    fun freshLedgerIsEmptyAndRememberSurvivesRecreation() {
        assertTrue(ids().isEmpty())
        assertFalse(ledger.exists())
        store().remember(owner, "debian", project, id)
        assertEquals(setOf(id), ids())
        assertTrue(ledger.isFile)
        assertFalse(File(temporary.root, ".agent").exists())
    }

    @Test
    fun ownerEnvironmentAndProjectAreIsolated() {
        store().remember(owner, "debian", project, id)
        assertTrue(ids(other).isEmpty())
        assertTrue(store().ids(owner, "alpine", project).isEmpty())
        assertTrue(store().ids(owner, "debian", "/workspace/Other").isEmpty())
        store().remember(other, "alpine", project, id)
        store().remember(other, "debian", "/workspace/Other", id)
        assertEquals(setOf(id), store().ids(other, "alpine", project))
        assertEquals(setOf(id), store().ids(other, "debian", "/workspace/Other"))
        assertTrue(ids(other).isEmpty())
        assertEquals(setOf(id), ids())
    }

    @Test
    fun batchBackfillAndRepeatedWritesAreIdempotent() {
        store().remember(owner, "debian", project, id)
        store().rememberAll(owner, "debian", project, setOf(id, nextId))
        val bytes = ledger.readBytes()
        store().remember(owner, "debian", project, id)
        store().rememberAll(owner, "debian", project, setOf(nextId, id))
        store().rememberAll(owner, "debian", project, emptySet())
        assertArrayEquals(bytes, ledger.readBytes())
        assertEquals(setOf(id, nextId), ids())
    }

    @Test
    fun conflictingOwnerCannotClaimTupleOrPartOfBatch() {
        store().remember(owner, "debian", project, id)
        val bytes = ledger.readBytes()
        val error = assertThrows(IllegalStateException::class.java) {
            store().remember(other, "debian", project, id)
        }
        assertEquals("Workspace ownership conflict", error.message)
        assertNull(error.cause)
        assertThrows(IllegalStateException::class.java) {
            store().rememberAll(other, "debian", project, linkedSetOf(nextId, id))
        }
        assertArrayEquals(bytes, ledger.readBytes())
        assertEquals(setOf(id), ids())
        assertTrue(ids(other).isEmpty())
    }

    @Test
    fun corruptOrUnsupportedLedgerFailsClosedWithoutOverwrite() {
        val record = """{"environment":"debian","project":"$project","id":"$id","owner":"$owner"}"""
        val invalidDocuments = listOf(
            "", "not-json", "{" + owner,
            """{"version":2,"entries":[]}""",
            """{"version":"1","entries":[]}""",
            """{"version":1,"entries":null}""",
            """{"version":1,"entries":[],"unexpected":true}""",
            """{"version":1,"entries":[{}]}""",
            """{"version":1,"entries":[$record,$record]}""",
        )
        for (document in invalidDocuments) {
            ledger.writeText(document)
            val error = assertThrows(IllegalStateException::class.java) { ids() }
            assertEquals("Workspace ownership ledger invalid or unsupported", error.message)
            assertNull(error.cause)
            assertThrows(IllegalStateException::class.java) {
                store().remember(owner, "debian", project, nextId)
            }
            assertThrows(IllegalStateException::class.java) {
                store().rememberAll(owner, "debian", project, emptySet())
            }
            assertEquals(document, ledger.readText())
        }
    }

    @Test
    fun concurrentInstancesRetainEveryWrite() {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val expected = (0 until 24).map { it.toString(16).padStart(32, '0') }.toSet()
        try {
            val futures = expected.map { workspaceId ->
                executor.submit {
                    start.await()
                    store().remember(owner, "debian", project, workspaceId)
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(expected, ids())
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun invalidInputsAndOversizeLedgerAreRejected() {
        for (environment in listOf("", "linux", "Debian", " alpine")) {
            assertThrows(IllegalArgumentException::class.java) { store().ids(owner, environment, project) }
        }
        for (path in listOf("/workspace", "/workspace/.", "/workspace/..", "/workspace/a/b", "/tmp/a")) {
            assertThrows(IllegalArgumentException::class.java) { store().ids(owner, "debian", path) }
        }
        for (workspaceId in listOf("", "A".repeat(32), "a".repeat(31), "g".repeat(32))) {
            assertThrows(IllegalArgumentException::class.java) {
                store().remember(owner, "debian", project, workspaceId)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ids(" ") }
        ledger.writeBytes(ByteArray(2 * 1024 * 1024 + 1))
        assertThrows(IllegalStateException::class.java) { ids() }
        assertThrows(IllegalStateException::class.java) { store().remember(owner, "debian", project, id) }
        assertEquals(2 * 1024 * 1024 + 1L, ledger.length())
    }

    @Test
    fun unavailableDirectoryOrLockDoesNotReturnEmptyOrReplaceLedger() {
        val missing = AgentWorkspaceOwnershipStore(File(temporary.root, "missing"))
        assertThrows(IllegalStateException::class.java) { missing.ids(owner, "debian", project) }
        store().remember(owner, "debian", project, id)
        val bytes = ledger.readBytes()
        val lock = File(temporary.root, "agent-workspace-ownership.json.lock")
        assertTrue(lock.delete())
        assertTrue(lock.mkdir())
        val error = assertThrows(IllegalStateException::class.java) { ids() }
        assertEquals("Workspace ownership ledger I/O failed", error.message)
        assertNull(error.cause)
        assertThrows(IllegalStateException::class.java) {
            store().remember(owner, "debian", project, nextId)
        }
        assertArrayEquals(bytes, ledger.readBytes())
    }

    @Test
    fun attributeAccessDeniedDoesNotReturnEmptyOrReplaceLedger() {
        assertAttributeFailurePreservesLedger(AccessDeniedException(ledger.path))
    }

    @Test
    fun attributeIoFailureDoesNotReturnEmptyOrReplaceLedger() {
        assertAttributeFailurePreservesLedger(IOException("Attribute lookup failed"))
    }

    private fun assertAttributeFailurePreservesLedger(failure: IOException) {
        store().remember(owner, "debian", project, id)
        val bytes = ledger.readBytes()
        val failingStore = AgentWorkspaceOwnershipStore(temporary.root, readAttributes = { path ->
            assertEquals(ledger.toPath(), path)
            throw failure
        })
        val readError = assertThrows(IllegalStateException::class.java) {
            failingStore.ids(owner, "debian", project)
        }
        assertEquals("Workspace ownership ledger I/O failed", readError.message)
        assertNull(readError.cause)
        assertArrayEquals(bytes, ledger.readBytes())
        val writeError = assertThrows(IllegalStateException::class.java) {
            failingStore.rememberAll(other, "debian", project, setOf(id, nextId))
        }
        assertEquals("Workspace ownership ledger I/O failed", writeError.message)
        assertNull(writeError.cause)
        assertArrayEquals(bytes, ledger.readBytes())
        assertEquals(setOf(id), ids())
        assertTrue(ids(other).isEmpty())
    }
}
