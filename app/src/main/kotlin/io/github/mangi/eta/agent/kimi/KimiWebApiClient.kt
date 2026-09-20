package io.github.mangi.eta.agent.kimi

import io.github.mangi.eta.agent.model.AgentHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Kimi Code 本机 Web API 的同步客户端。
 *
 * 与"在容器里跑 `kimi --prompt` 无头命令"的旧实现相比，直连 REST 有三个决定性优势：
 * 1. 走的是服务端已建立的会话与上下文，不必每次冷启动 Node 运行时；
 * 2. 手机侧无需长时间持有前台终端会话，避免被系统回收导致 `KIMI_EXITED`；
 * 3. 提示词与图片通过 JSON 递交，规避了 shell 引号转义造成的指令损坏。
 *
 * 端点契约见 [KimiWebEndpoint]；所有响应都是 `{code,msg,data,request_id}` 信封，
 * `code == 0` 表示成功，业务数据在 `data` 内。
 */
internal class KimiWebApiClient(
    private val origin: String,
    private val token: String?,
    private val httpClient: okhttp3.OkHttpClient = defaultClient,
) {

    private val apiBase: String = origin.trimEnd('/') + "/api/v1"

    /** 创建会话；`metadata.cwd` 决定工作目录（服务端据此注册 workspace）。 */
    fun createSession(cwd: String, title: String? = null): KimiSession {
        val body = JSONObject().apply {
            put("metadata", JSONObject().put("cwd", cwd))
            title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
        }
        val data = post("/sessions", body)
        return KimiSession.from(data)
    }

    /** 提交提示词。文本与附件统一编码为 content 数组。 */
    fun submitPrompt(
        sessionId: String,
        text: String,
        attachments: List<KimiAttachment> = emptyList(),
        model: String? = null,
        permissionMode: String? = null,
    ): KimiPrompt {
        val content = JSONArray()
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        attachments.forEach { attachment -> content.put(attachment.toWireJson()) }
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }
        val body = JSONObject().apply {
            put("content", content)
            model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
            permissionMode?.takeIf { it.isNotBlank() }?.let { put("permission_mode", it) }
        }
        val data = post("/sessions/${encode(sessionId)}/prompts", body)
        return KimiPrompt.from(data)
    }

    /** 读取会话状态：`busy` 表示是否仍在执行本轮。 */
    fun sessionStatus(sessionId: String): KimiSessionStatus {
        val data = get("/sessions/${encode(sessionId)}/status")
        return KimiSessionStatus(
            busy = data.optBoolean("busy", false),
            model = data.optString("model").takeIf { it.isNotBlank() },
            contextTokens = data.optInt("context_tokens", 0),
            maxContextTokens = data.optInt("max_context_tokens", 0),
        )
    }

    /** 拉取消息列表；`before_id` 为向前翻页游标。 */
    fun listMessages(sessionId: String, limit: Int = 20, beforeId: String? = null): List<KimiMessage> {
        val query = buildString {
            append("?limit=").append(limit)
            beforeId?.takeIf { it.isNotBlank() }?.let { append("&before_id=").append(encode(it)) }
        }
        val data = get("/sessions/${encode(sessionId)}/messages$query")
        val items = data.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let { add(KimiMessage.from(it)) }
            }
        }
    }

    /** 中止当前轮次（`POST /prompts/abort`）。 */
    fun abort(sessionId: String): Boolean {
        val data = post("/sessions/${encode(sessionId)}/prompts/abort", JSONObject())
        return data.optBoolean("aborted", false)
    }

    /** 关闭本机服务（`--allow-remote-shutdown` 未开启时服务端返回 404）。 */
    fun shutdown(): Boolean = runCatching {
        post("/shutdown", JSONObject())
        true
    }.getOrDefault(false)

    private fun get(path: String): JSONObject = request("GET", path, null)

    private fun post(path: String, body: JSONObject): JSONObject = request("POST", path, body)

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder().url("$apiBase$path")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.get()
            else -> builder.post(
                (body ?: JSONObject()).toString().toRequestBody(JSON_MEDIA_TYPE),
            )
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw KimiWebApiException(
                    code = "HTTP_${response.code}",
                    message = extractMessage(payload).ifBlank { "HTTP ${response.code}" },
                )
            }
            val envelope = runCatching { JSONObject(payload) }.getOrNull()
                ?: throw KimiWebApiException("INVALID_RESPONSE", "响应不是合法 JSON")
            val code = envelope.optInt("code", -1)
            if (code != 0) {
                throw KimiWebApiException(
                    code = code.toString(),
                    message = envelope.optString("msg").ifBlank { "Kimi 服务返回 code=$code" },
                )
            }
            return envelope.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun extractMessage(payload: String): String =
        runCatching { JSONObject(payload).optString("msg") }.getOrDefault("")

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * REST 调用是短连接，但首轮提示词会触发模型与工具编排，
         * 因此写入/读取超时都要比普通网络请求宽松。
         */
        private val defaultClient: okhttp3.OkHttpClient by lazy {
            AgentHttpClient.client.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}

/** Kimi 服务返回的业务错误（信封 code 非 0，或 HTTP 层失败）。 */
internal class KimiWebApiException(
    val code: String,
    override val message: String,
) : IOException("Kimi Web API $code: $message")

/** 会话摘要。 */
internal data class KimiSession(
    val id: String,
    val workspaceId: String,
    val title: String,
    val cwd: String,
) {
    companion object {
        fun from(json: JSONObject): KimiSession {
            val metadata = json.optJSONObject("metadata")
            return KimiSession(
                id = json.optString("id"),
                workspaceId = json.optString("workspace_id"),
                title = json.optString("title"),
                cwd = metadata?.optString("cwd").orEmpty(),
            )
        }
    }
}

/** 已入队的提示词。 */
internal data class KimiPrompt(
    val promptId: String,
    val userMessageId: String,
    val status: String,
) {
    companion object {
        fun from(json: JSONObject): KimiPrompt = KimiPrompt(
            promptId = json.optString("prompt_id"),
            userMessageId = json.optString("user_message_id"),
            status = json.optString("status"),
        )
    }
}

internal data class KimiSessionStatus(
    val busy: Boolean,
    val model: String?,
    val contextTokens: Int,
    val maxContextTokens: Int,
)

internal data class KimiMessage(
    val id: String,
    val role: String,
    val text: String,
) {
    companion object {
        fun from(json: JSONObject): KimiMessage {
            val content = json.optJSONArray("content")
            val text = buildString {
                if (content != null) {
                    for (index in 0 until content.length()) {
                        val part = content.optJSONObject(index) ?: continue
                        when (part.optString("type")) {
                            "text" -> append(part.optString("text"))
                            "thinking" -> Unit
                            else -> Unit
                        }
                    }
                }
            }
            return KimiMessage(
                id = json.optString("id"),
                role = json.optString("role"),
                text = text,
            )
        }
    }
}

/**
 * 随提示词提交的附件。
 *
 * 契约取证结论（`messageContentSchema` / `imageSourceSchema`）：
 * - 图片：`{"type":"image","source":{"kind":"base64","media_type","data"},"name"}`
 *   或 `{"type":"image","source":{"kind":"path","path"}}`；
 * - 文件：`{"type":"file","path","name","media_type"}`。
 *
 * 图片优先走 base64 内联，避免依赖容器内可读的宿主机路径；
 * 文件走 path，因为 Kimi 的 file 类型只接受 file_id 或服务端本地绝对路径。
 */
internal sealed interface KimiAttachment {

    data class ImageBase64(
        val mediaType: String,
        val base64Data: String,
        val name: String? = null,
    ) : KimiAttachment

    data class ImagePath(
        val path: String,
        val name: String? = null,
    ) : KimiAttachment

    data class File(
        val path: String,
        val name: String? = null,
        val mediaType: String? = null,
    ) : KimiAttachment

    fun toWireJson(): JSONObject = when (this) {
        is ImageBase64 -> JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("kind", "base64")
                    .put("media_type", mediaType)
                    .put("data", base64Data),
            )
            .also { json -> name?.takeIf { it.isNotBlank() }?.let { json.put("name", it) } }
        is ImagePath -> JSONObject()
            .put("type", "image")
            .put("source", JSONObject().put("kind", "path").put("path", path))
            .also { json -> name?.takeIf { it.isNotBlank() }?.let { json.put("name", it) } }
        is File -> JSONObject()
            .put("type", "file")
            .put("path", path)
            .also { json -> name?.takeIf { it.isNotBlank() }?.let { json.put("name", it) } }
            .also { json -> mediaType?.takeIf { it.isNotBlank() }?.let { json.put("media_type", it) } }
    }
}