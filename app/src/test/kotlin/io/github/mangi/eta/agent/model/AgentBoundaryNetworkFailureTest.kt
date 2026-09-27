package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class AgentBoundaryNetworkFailureTest {
    @Test fun queuedBoundaryGuidanceCannotConvertProviderFailureIntoSuccess() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/reject") { exchange ->
            val body = "temporary upstream failure".toByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val controller = AgentRunController()
            assertTrue(controller.queueBoundaryGuidance("inspect outcome"))
            assertThrows(AgentModelFailure::class.java) {
                AgentSseClient.collect(
                    Request.Builder().url("http://127.0.0.1:${server.address.port}/reject").build(),
                    controller,
                    onEvent = { _, _, _ -> fail("503 must not yield events") },
                )
            }
            assertEquals("inspect outcome", controller.pollSteeringMessage())
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
