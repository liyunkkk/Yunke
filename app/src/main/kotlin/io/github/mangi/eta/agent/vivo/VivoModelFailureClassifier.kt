package io.github.mangi.eta.agent.vivo

import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentOutputLimitException
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/** Pure, bounded metadata projection. Never inspect exception text or provider diagnostics. */
internal object VivoModelFailureClassifier {
    enum class Category {
        HTTP_400, HTTP_401, HTTP_403, HTTP_404, HTTP_408, HTTP_413, HTTP_429,
        HTTP_5XX, HTTP_OTHER, DNS, TLS, CONNECT, IO, PARSE, CANCEL, CONFIG, UNKNOWN,
        OUTPUT_LIMIT, EMPTY_RESPONSE,
    }

    // Only exact codes on a typed AgentModelFailure may enter this vocabulary.
    enum class ModelCode {
        MODEL_TIMEOUT, MODEL_CONNECTION_FAILED, CONTEXT_WINDOW_EXCEEDED,
        PROVIDER_STREAM_ERROR, STREAM_INCOMPLETE, OUTPUT_LIMIT, EMPTY_RESPONSE,
    }

    data class Classification(
        val category: Category,
        // Number in AgentModelFailure.code, NOT proof of an observed HTTP response.
        // unexpectedResponse may synthesize HTTP_200 when the actual status is absent.
        val httpCode: Int? = null,
        val modelCode: ModelCode? = null,
    ) {
        init { require(httpCode == null || httpCode in 100..599) }
    }

    const val MAX_CAUSES = 8
    private val httpCode = Regex("HTTP_[1-5][0-9]{2}")

    fun classify(error: Throwable, cancelled: Boolean = false): Classification {
        if (cancelled) return Classification(Category.CANCEL)
        val seen = ArrayList<Throwable>(MAX_CAUSES)
        var current: Throwable? = error
        var transport: Category? = null
        var typed: Classification? = null
        while (current != null && seen.size < MAX_CAUSES) {
            val item = current
            // Identity, not Throwable.equals/hashCode (both can be overridden).
            if (seen.any { it === item }) break
            seen.add(item)
            when (item) {
                is AgentRunCancelledException, is CancellationException, is InterruptedException ->
                    return Classification(Category.CANCEL)
                is AgentModelFailure -> {
                    // This revision has a structured code, not a status property. Match the
                    // WHOLE field before extracting; no free-text HTTP inference anywhere.
                    if (httpCode.matches(item.code)) {
                        val status = item.code.substring(5).toInt()
                        return Classification(httpCategory(status), httpCode = status)
                    }
                    val code = ModelCode.entries.firstOrNull { it.name == item.code }
                    if (code != null && typed == null) typed = Classification(when (code) {
                        ModelCode.MODEL_TIMEOUT, ModelCode.MODEL_CONNECTION_FAILED -> Category.IO
                        ModelCode.OUTPUT_LIMIT -> Category.OUTPUT_LIMIT
                        ModelCode.EMPTY_RESPONSE -> Category.EMPTY_RESPONSE
                        else -> Category.UNKNOWN
                    }, modelCode = code)
                }
                is AgentOutputLimitException -> return Classification(Category.OUTPUT_LIMIT)
                is UnknownHostException -> return Classification(Category.DNS, modelCode = typed?.modelCode)
                is SSLException -> return Classification(Category.TLS, modelCode = typed?.modelCode)
                is ConnectException, is NoRouteToHostException ->
                    return Classification(Category.CONNECT, modelCode = typed?.modelCode)
                is JSONException -> return Classification(Category.PARSE)
                // SocketTimeoutException/InterruptedIOException alone do not prove cancellation.
                is IOException -> transport = Category.IO
            }
            // Even a pathological overridden cause getter cannot break the result path.
            current = try { item.cause } catch (_: Throwable) { null }
        }
        return if (transport != null && (typed == null || typed.category == Category.IO)) {
            Classification(transport, modelCode = typed?.modelCode)
        } else typed ?: Classification(Category.UNKNOWN)
    }

    private fun httpCategory(status: Int): Category = when (status) {
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
}
