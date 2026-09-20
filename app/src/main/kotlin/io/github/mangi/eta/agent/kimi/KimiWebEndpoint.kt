package io.github.mangi.eta.agent.kimi

/**
 * Kimi Code（`@moonshot-ai/kimi-code`）本机 Web 服务端的端点与凭据解析。
 *
 * 真实契约（来自 2.0.0 发行包 `dist/main.mjs` 取证）：
 * - `kimi web` 默认绑定 `127.0.0.1:58627`；`--host` 通配绑定时横幅打印 `localhost`；
 *   端口被占用时 `listenWithPortRetry` 会向高位端口逐个重试（最多 100 次）；
 * - 所有 REST 路由挂在 `/api/v1` 前缀下，鉴权头为 `Authorization: Bearer <token>`；
 * - Token 由 `randomBytes(32).toString("base64url")` 生成，持久化在
 *   `<KIMI_CODE_HOME>/server.token`，启动横幅同时以两种形式暴露：
 *   带注解的 `Token:    <token>` 行，以及 URL 片段 `http://<host>:<port>/#token=<token>`。
 *
 * 关键实现细节：横幅经 chalk 分色输出，`urlWithDimToken()` 会对 URL 主体与 `#token=`
 * 片段分别上色，因此两者之间（以及标签与 URL 之间）会插入 ANSI 转义序列。
 * 历史实现直接用 `http://127\.0\.0\.1:\d+/#token=` 匹配原始文本，颜色生效即无法命中，
 * 表现为"Kimi 启动失败 / URL_TIMEOUT"。本对象统一先剥离 ANSI 再解析。
 */
internal object KimiWebEndpoint {

    /** 默认端口；`--port` 未显式指定时服务端使用该值。 */
    const val DEFAULT_PORT = 58627

    private const val HOST_LOOPBACK = "127.0.0.1"

    /** CSI / OSC / 单字符 ESC 序列；覆盖 chalk 与日志回读时的常见输出。 */
    private val ANSI_REGEX = Regex(
        "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]",
    )

    /**
     * 启动横幅里可能出现的 URL 形态。host 覆盖回环别名与通配绑定，
     * 端口限定合法区间，token 使用 base64url 字符集。
     */
    private val URL_REGEX = Regex(
        "https?://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]|0\\.0\\.0\\.0|::)" +
            "(?::(\\d{1,5}))?/?(?:#token=([A-Za-z0-9._~+=-]+))?",
    )

    /** 注解形式的 Token 行：`Token:    <value>`（容忍缩进与引号）。 */
    private val ANNOTATED_TOKEN_REGEX =
        Regex("(?im)^\\s*Token\\s*[:=]\\s*[\"']?([A-Za-z0-9._~+=-]{8,})[\"']?\\s*$")

    private val TOKEN_FRAGMENT_REGEX = Regex("[#?&]token=([A-Za-z0-9._~+=-]+)")

    internal data class Endpoint(
        val origin: String,
        val token: String?,
    ) {
        /** 带 `/api/v1` 前缀的 REST 根地址。 */
        val apiBase: String get() = "$origin/api/v1"

        /** 浏览器地址：保留 token 片段供 Web UI 自鉴权。 */
        val openableUrl: String
            get() = if (token.isNullOrBlank()) "$origin/" else "$origin/#token=$token"
    }

    /** 剥离 ANSI 控制序列，避免颜色码打断 URL / Token 的连续匹配。 */
    fun stripAnsi(raw: String): String = raw.replace(ANSI_REGEX, "")

    /**
     * 从守护进程日志解析端点。优先采用 URL 行的主机与端口（它反映实际绑定结果）；
     * URL 缺失时回退到默认回环端口，仅识别到 Token 也视为可连接。
     */
    fun parse(rawLogs: String?): Endpoint? {
        val text = stripAnsi(rawLogs.orEmpty())
        if (text.isBlank()) return null

        val match = URL_REGEX.find(text)
        val host = hostOf(match?.value) ?: HOST_LOOPBACK

        val port = match?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: DEFAULT_PORT

        val token = match?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: parseToken(text)

        if (match == null && token == null) return null

        val normalizedHost = normalizeHost(host)
        return Endpoint(origin = "http://$normalizedHost:$port", token = token)
    }

    /** 提取 Bearer Token：先取 URL 片段，再退回带注解的 `Token:` 行。 */
    fun parseToken(rawLogs: String?): String? {
        val text = stripAnsi(rawLogs.orEmpty())
        if (text.isBlank()) return null
        TOKEN_FRAGMENT_REGEX.find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return ANNOTATED_TOKEN_REGEX.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * 从完整 URL 中取出 host。
     *
     * 注意不能简单 `substringBefore(':')`：通配绑定会打印成 `[::1]`，
     * 首个冒号出现在 IPv6 字面量内部，粗暴切分只会得到 `[`。
     */
    private fun hostOf(url: String?): String? {
        val authority = url
            ?.substringAfter("://", "")
            ?.substringBefore('/')
            ?.substringBefore('#')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        // IPv6 字面量：保留方括号形式，端口在 `]` 之后。
        if (authority.startsWith("[") && authority.contains(']')) {
            return authority.substringBefore(']') + "]"
        }
        return authority.substringBefore(':').takeIf { it.isNotBlank() }
    }

    /**
     * 归一化 host 为手机侧可达的回环地址。
     *
     * 服务端 `--host` 通配绑定时横幅可能打印 `0.0.0.0` 或 `::`，
     * 这些地址不能直接用于客户端连接，必须折算到回环。
     */
    private fun normalizeHost(host: String): String = when (host) {
        "0.0.0.0", "::", "[::]" -> HOST_LOOPBACK
        "::1", "[::1]", "localhost" -> HOST_LOOPBACK
        else -> host
    }

    /** 兼容旧调用点：只需要可打开地址时使用。 */
    fun parseOpenableUrl(rawLogs: String?): String? =
        parse(rawLogs)?.takeIf { !it.token.isNullOrBlank() }?.openableUrl
}
