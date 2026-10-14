package io.github.mangi.eta.agent.model

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.asResponseBody
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp clients.
 *
 * Timeouts, connection retry and JSON Content-Type follow RikkaHub:
 * some gateways reject `application/json; charset=utf-8`, and a short
 * write timeout can fail large context uploads before the stream starts.
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val READ_TIMEOUT_MS = 600_000L
    private const val WRITE_TIMEOUT_MS = 120_000L
    const val MODEL_READ_TIMEOUT_MS = READ_TIMEOUT_MS

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(ProviderRequestInterceptor)
            .addNetworkInterceptor(JsonContentTypeInterceptor)
            .addNetworkInterceptor(SseContentTypeInterceptor)
            .build()
    }

    val modelClient: OkHttpClient by lazy { client }

    /**
     * Antigravity / Cloud Code Assist 专用：强制 HTTP/1.1 单连接。
     *
     * 意图：原生 Antigravity 客户端以 HTTP/1.1 串行请求，Cloud Code 网关据此判定客户端身份；
     * 指纹集对齐 CLIProxyAPI（PR #1257 同源）。
     * 不这么做后果：HTTP/2 多路复用与原生指纹不符，长会话续轮更易被上游拒绝。
     */
    val antigravityClient: OkHttpClient by lazy {
        client.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }
}

private object ProviderRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val builder = original.newBuilder()
        if (original.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", "Yunke-Android")
        }
        val host = original.url.host
        if (host == "openrouter.ai" || host.endsWith(".openrouter.ai")) {
            if (original.header("X-Title").isNullOrBlank()) {
                builder.header("X-Title", "Yunke")
            }
            if (original.header("HTTP-Referer").isNullOrBlank()) {
                builder.header("HTTP-Referer", "https://github.com/liyunkkk/Yunke")
            }
        }
        return chain.proceed(builder.build())
    }
}

private object JsonContentTypeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val contentType = request.header("Content-Type") ?: return chain.proceed(request)
        val rawType = contentType.substringBefore(";").trim()
        if (!rawType.equals("application/json", ignoreCase = true) || !contentType.contains(";")) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .header("Content-Type", "application/json")
                .build(),
        )
    }
}


private object SseContentTypeInterceptor : Interceptor {
    private val eventStream = "text/event-stream".toMediaType()

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val accept = request.header("Accept").orEmpty()
        if (!accept.contains("text/event-stream", ignoreCase = true)) return response
        if (accept.contains("application/json", ignoreCase = true)) return response
        if (!response.isSuccessful) return response
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.contains("text/event-stream", ignoreCase = true)) return response
        if (contentType.isNotBlank()) return response
        val body = response.body ?: return response
        return response.newBuilder()
            .header("Content-Type", "text/event-stream")
            .body(body.source().asResponseBody(eventStream, body.contentLength()))
            .build()
    }
}
