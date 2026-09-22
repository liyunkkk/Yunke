package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class AgentSseClientBudgetPauseTest {
    @Test fun budgetPauseDrainsTheSameStreamAndResumeDoesNotReplayIt() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        server.executor = executor
        server.createContext("/stream") { exchange ->
            requests.incrementAndGet()
            val body = "data: first\n\ndata: second\n\ndata: last\n\n".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val controller = AgentRunController()
        val drained = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "sse-budget-collector") {
            try {
                AgentSseClient.collect(
                    Request.Builder().url("http://127.0.0.1:${server.address.port}/stream").build(),
                    controller,
                    onEvent = { _, _, data ->
                        events += data
                        if (data == "first") controller.pauseAtCheckpoint()
                        // A nested provider/progress callback must not block the network thread.
                        controller.throwIfCancelled()
                        if (data == "last") { finish(); drained.countDown() }
                    },
                )
            } catch (t: Throwable) { failure.set(t) }
            finally { finished.countDown() }
        }
        try {
            assertTrue("the same SSE must fully drain during budget pause", drained.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("first", "second", "last"), events.toList())
            assertFalse("only the collecting worker waits for continue", finished.await(100, TimeUnit.MILLISECONDS))
            controller.resume()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(1, requests.get())
        } finally {
            controller.cancel()
            worker.join(2_000)
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
