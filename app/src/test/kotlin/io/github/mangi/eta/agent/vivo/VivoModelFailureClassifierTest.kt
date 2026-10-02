package io.github.mangi.eta.agent.vivo

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentOutputLimitException
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import io.github.mangi.eta.agent.vivo.VivoModelFailureClassifier.Category
import io.github.mangi.eta.agent.vivo.VivoModelFailureClassifier.ModelCode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VivoModelFailureClassifierTest {
    private val secret = "HTTP_401 https://private.invalid/api Authorization: Bearer private-key body=private"
    private fun model(code: String, cause: Throwable? = null) =
        AgentModelFailure(code, false, secret, cause, diagnostic = secret)

    @Test fun everyStructuredHttpStatusUsesTheClosedFamilyAndBoundedInteger() {
        for (status in 100..599) {
            val result = VivoModelFailureClassifier.classify(model("HTTP_$status"))
            val expected = when (status) {
                400 -> Category.HTTP_400
                401 -> Category.HTTP_401
                403 -> Category.HTTP_403
                404 -> Category.HTTP_404
                408 -> Category.HTTP_408
                413 -> Category.HTTP_413
                429 -> Category.HTTP_429
                in 500..599 -> Category.HTTP_5XX
                else -> Category.HTTP_OTHER
            }
            assertEquals(expected, result.category)
            assertEquals(status, result.httpCode)
            assertNull(result.modelCode)
        }
    }

    @Test fun syntheticHttp200IsOnlyAClassifiedErrorCodeNotAClaimOfHttpSuccess() {
        val result = VivoModelFailureClassifier.classify(
            AgentModelFailure.unexpectedResponse(status = null, contentType = "text/plain", body = ""))
        assertEquals(Category.HTTP_OTHER, result.category)
        assertEquals(200, result.httpCode)
    }

    @Test fun fullCodeAndRuntimeTypeAreRequiredNotMessagesOrLookalikeFields() {
        val invalid = listOf("HTTP_099", "HTTP_600", "HTTP_1000", "HTTP_0401", "HTTP_+401",
            "http_401", " HTTP_401", "HTTP_401 ", "HTTP_401\n", "HTTP_401\r", "HTTP_401\u0000",
            "HTTP_401_private", "private_HTTP_401", "HTTP_４０１", secret)
        invalid.forEach { code ->
            val result = VivoModelFailureClassifier.classify(model(code))
            assertEquals(Category.UNKNOWN, result.category)
            assertNull(result.httpCode)
            assertNull(result.modelCode)
        }
        class Lookalike : IllegalStateException(secret) { val code = "HTTP_401"; val status = 401 }
        assertEquals(Category.UNKNOWN, VivoModelFailureClassifier.classify(Lookalike()).category)
        assertEquals(Category.UNKNOWN, VivoModelFailureClassifier.classify(IllegalArgumentException(secret)).category)
        assertEquals(Category.UNKNOWN, VivoModelFailureClassifier.classify(IllegalStateException(secret)).category)
    }

    @Test fun knownTypesDoNotDependOnExceptionTextOrElapsedTime() {
        val cases = listOf(
            UnknownHostException(secret) to Category.DNS,
            SSLException(secret) to Category.TLS,
            ConnectException(secret) to Category.CONNECT,
            NoRouteToHostException(secret) to Category.CONNECT,
            IOException(secret) to Category.IO,
            SocketTimeoutException(secret) to Category.IO,
            InterruptedIOException(secret) to Category.IO,
            JSONException(secret) to Category.PARSE,
            AgentRunCancelledException() to Category.CANCEL,
            CancellationException(secret) to Category.CANCEL,
            InterruptedException(secret) to Category.CANCEL,
            AgentOutputLimitException(secret) to Category.OUTPUT_LIMIT,
            AssertionError(secret) to Category.UNKNOWN,
            IllegalStateException("161ms timeout HTTP 401 $secret") to Category.UNKNOWN,
        )
        cases.forEach { (error, category) ->
            assertEquals(category, VivoModelFailureClassifier.classify(error).category)
            assertEquals(category, VivoModelFailureClassifier.classify(RuntimeException(secret, error)).category)
        }
        assertEquals(Category.CANCEL, VivoModelFailureClassifier.classify(model("HTTP_401"), cancelled = true).category)
    }

    @Test fun structuredModelCodesUseOnlyAnExactWhitelistAndKeepSpecificTransportCauses() {
        ModelCode.entries.forEach { code ->
            val result = VivoModelFailureClassifier.classify(model(code.name))
            assertEquals(code, result.modelCode)
            assertNull(result.httpCode)
        }
        assertEquals(Category.EMPTY_RESPONSE, VivoModelFailureClassifier.classify(model("EMPTY_RESPONSE")).category)
        assertEquals(Category.OUTPUT_LIMIT, VivoModelFailureClassifier.classify(model("OUTPUT_LIMIT")).category)
        assertEquals(Category.UNKNOWN, VivoModelFailureClassifier.classify(model("CONTEXT_WINDOW_EXCEEDED")).category)
        val result = VivoModelFailureClassifier.classify(model("MODEL_CONNECTION_FAILED", UnknownHostException(secret)))
        assertEquals(Category.DNS, result.category)
        assertEquals(ModelCode.MODEL_CONNECTION_FAILED, result.modelCode)
        assertEquals(Category.HTTP_429, VivoModelFailureClassifier.classify(model("HTTP_429", IOException(secret))).category)
        assertNull(VivoModelFailureClassifier.classify(model("EMPTY_RESPONSE\n")).modelCode)
    }

    private class Node : RuntimeException() {
        var next: Throwable? = null
        var reads = 0
        override val cause: Throwable? get() { reads++; return next }
        override val message: String get() = error("must not read message")
        override fun toString(): String = error("must not format exception")
        override fun equals(other: Any?): Boolean = error("must use identity")
        override fun hashCode(): Int = error("must use identity")
    }

    @Test fun selfAndMultiNodeCyclesTerminateUsingIdentity() {
        val self = Node().apply { next = this }
        assertEquals(Category.UNKNOWN, VivoModelFailureClassifier.classify(self).category)
        assertEquals(1, self.reads)
        val a = Node()
        val b = Node()
        a.next = b; b.next = a
        assertEquals(Category.UNKNOWN, VivoModelFailureClassifier.classify(a).category)
        assertEquals(1, a.reads)
        assertEquals(1, b.reads)
    }

    @Test fun traversalIsBoundedAndCanFindOnlyEvidenceWithinTheBound() {
        val nodes = List(VivoModelFailureClassifier.MAX_CAUSES) { Node() }
        nodes.zipWithNext().forEach { (a, b) -> a.next = b }
        nodes.last().next = UnknownHostException(secret)
        assertEquals(Category.UNKNOWN, VivoModelFailureClassifier.classify(nodes.first()).category)
        assertEquals(VivoModelFailureClassifier.MAX_CAUSES, nodes.sumOf { it.reads })
        nodes[nodes.lastIndex - 1].next = UnknownHostException(secret)
        assertEquals(Category.DNS, VivoModelFailureClassifier.classify(nodes.first()).category)
    }

    @Test fun brokenCauseAccessorCannotReplaceTheFailure() {
        val error = object : RuntimeException() {
            override val cause: Throwable get() = throw AssertionError(secret)
            override val message: String get() = error("must not read message")
        }
        assertEquals(Category.UNKNOWN, VivoModelFailureClassifier.classify(error).category)
    }
}
