package io.github.mangi.eta.agent.browser.ported.browser

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChildBrowserDownloadsTest {
    private fun server(body: ByteArray, action: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, if(body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try { action("http://127.0.0.1:${server.address.port}/file") }
        finally { server.stop(0) }
    }
    @Test fun streamedFilesHaveUniqueNamesAndEmptyBodiesAreValid() {
        val directory = Files.createTempDirectory("child-download-fixture").toFile()
        try {
            val store = ChildBrowserDownloads({ directory }, { null })
            server("fixture body".toByteArray()) { url -> runBlocking {
                coroutineScope {
                    val one = async { store.fetch(url) }; val two = async { store.fetch(url) }
                    val first = one.await(); val second = two.await()
                    assertNotEquals(first.file.name, second.file.name)
                    assertEquals("fixture body", first.file.readText())
                    assertEquals("fixture body", second.file.readText())
                    assertEquals(directory.canonicalFile, first.file.parentFile.canonicalFile)
                }
            } }
            server(ByteArray(0)) { url -> runBlocking { assertEquals(0L, store.fetch(url).size) } }
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
            store.close()
        } finally { directory.deleteRecursively() }
    }
    @Test fun declaredOversizeAndBadUrlsRefuseBeforeCreatingFiles() = runBlocking {
        val directory = Files.createTempDirectory("child-download-refusal").toFile()
        val store = ChildBrowserDownloads({ directory }, { null })
        try {
            for (url in listOf("file:///secret", "https://user:secret@example.org", "content://private")) {
                try { store.fetch(url); fail("should refuse") }
                catch(error: ChildBrowserDownloads.Refused) { assertEquals("CHILD_DOWNLOAD_URL_NOT_ALLOWED", error.code) }
            }
            try { store.fetch("https://example.org", declaredLength = ChildBrowserDownloads.MAX_BYTES + 1); fail("should refuse") }
            catch(error: ChildBrowserDownloads.Refused) { assertEquals("CHILD_DOWNLOAD_TOO_LARGE", error.code) }
            assertEquals(0, directory.listFiles().orEmpty().size)
        } finally { store.close(); directory.deleteRecursively() }
    }
    @Test fun closePreventsLateBlobWritesAndPartialFiles() = runBlocking {
        val directory = Files.createTempDirectory("child-download-closed").toFile()
        val store = ChildBrowserDownloads({ directory }, { null })
        store.close()
        try {
            try { store.blob("late".toByteArray()); fail("closed store must not write") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(0, directory.listFiles().orEmpty().size)
        } finally { directory.deleteRecursively() }
    }

    private fun freeSlots(budget: ChildDownloadBudget): Int {
        val held = ArrayList<ChildDownloadBudget.Lease>()
        while (true) { val lease = budget.tryAcquire() ?: break; held.add(lease) }
        held.forEach { it.release() }
        return held.size
    }

    @Test fun sharedBudgetAdmitsThreeRefusesTheFourthAndIgnoresDuplicateRelease() {
        val budget = ChildDownloadBudget()
        val first = budget.tryAcquire()
        val second = budget.tryAcquire()
        val third = budget.tryAcquire()
        assertNotNull(first); assertNotNull(second); assertNotNull(third)
        assertNull(budget.tryAcquire())
        first!!.release()
        first.release() // a duplicate release must not create a second free slot
        assertEquals(1, freeSlots(budget))
        second!!.release(); third!!.release()
        assertEquals(3, freeSlots(budget))
    }

    @Test fun httpFetchAndBlobShareOneBudgetAndRefuseTheFourthWithoutQueueing() = runBlocking {
        val directory = Files.createTempDirectory("child-download-budget").toFile()
        val budget = ChildDownloadBudget()
        val store = ChildBrowserDownloads({ directory }, { null }, budget)
        val held = (1..3).map { budget.tryAcquire() }
        try {
            assertNotNull(held[0]); assertNotNull(held[1]); assertNotNull(held[2])
            // Full budget: both the HTTP fetch and a blob that must acquire its own
            // slot refuse instead of building an unbounded queue.
            try { store.fetch("https://example.org"); fail("should refuse a full budget") }
            catch (error: ChildBrowserDownloads.Refused) { assertEquals("CHILD_DOWNLOAD_BUSY", error.code) }
            try { store.blob("x".toByteArray()); fail("should refuse a full budget") }
            catch (error: ChildBrowserDownloads.Refused) { assertEquals("CHILD_DOWNLOAD_BUSY", error.code) }
            assertEquals(0, directory.listFiles().orEmpty().size)
            // A slot already held by the page reader is reused, not re-acquired: the
            // save proceeds while the shared budget is otherwise still full.
            val saved = store.blob("held".toByteArray(), held[0])
            assertEquals("held", saved.file.readText())
            assertEquals(1, freeSlots(budget)) // save released held[0]; held[1..2] still held
        } finally { held.forEach { it?.release() }; store.close(); directory.deleteRecursively() }
    }

    @Test fun heldBlobLeaseIsReleasedOnEarlyRefusalAndOnCancellation() = runBlocking {
        val directory = Files.createTempDirectory("child-download-lease").toFile()
        val budget = ChildDownloadBudget()
        val store = ChildBrowserDownloads({ directory }, { null }, budget)
        try {
            val oversize = budget.tryAcquire()!!
            try { store.blob(ByteArray(ChildBrowserDownloads.MAX_BYTES.toInt() + 1), oversize); fail("should refuse") }
            catch (error: ChildBrowserDownloads.Refused) { assertEquals("CHILD_DOWNLOAD_TOO_LARGE", error.code) }
            assertEquals(3, freeSlots(budget))
            val cancelled = budget.tryAcquire()!!
            store.close()
            try { store.blob("late".toByteArray(), cancelled); fail("closed store must not write") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(3, freeSlots(budget))
        } finally { directory.deleteRecursively() }
    }

    @Test fun foreignReleasedAndRepeatedSavePermitsCannotBypassTheBudget() = runBlocking {
        val first = ChildDownloadBudget(); val second = ChildDownloadBudget()
        val foreign = requireNotNull(first.tryAcquire())
        assertFalse(foreign.claimForSave(second))
        assertTrue(foreign.claimForSave(first))
        assertFalse(foreign.claimForSave(first))
        foreign.release(); foreign.release()
        assertFalse(foreign.claimForSave(first))
        val held = List(3) { requireNotNull(first.tryAcquire()) }
        assertNull(first.tryAcquire())
        held.forEach { it.release() }
    }
}
