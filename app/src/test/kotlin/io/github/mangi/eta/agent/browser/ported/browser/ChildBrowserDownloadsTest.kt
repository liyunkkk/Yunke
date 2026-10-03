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
}
