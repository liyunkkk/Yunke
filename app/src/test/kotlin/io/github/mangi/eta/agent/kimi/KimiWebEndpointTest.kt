package io.github.mangi.eta.agent.kimi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kimi Web 启动横幅解析测试。
 *
 * 最关键的一条：Kimi 的横幅经 chalk 分色输出，`urlWithDimToken()` 会在
 * URL 主体与 `#token=` 片段之间插入 ANSI 转义序列。早期实现直接对原始文本做
 * `http://127\.0\.0\.1:\d+/#token=` 匹配，颜色生效后必然失配，现场表现就是
 * "Kimi 启动失败 / URL_TIMEOUT"，而日志里明明打印着地址。
 */
class KimiWebEndpointTest {

    private val esc = "\u001B"

    /** 真实 token 由 `randomBytes(32).toString("base64url")` 生成（约 43 字符）。 */
    private val token = "dGhpcy1pcy1hLXRlc3QtdG9rZW4tZm9yLWtpbWk"

    @Test
    fun parsesPlainBanner() {
        val endpoint = KimiWebEndpoint.parse("Kimi Code\n  http://127.0.0.1:58627/#token=$token\n")
        assertEquals("http://127.0.0.1:58627", endpoint?.origin)
        assertEquals(token, endpoint?.token)
    }

    @Test
    fun parsesBannerSplitByAnsiEscapeBetweenUrlAndToken() {
        // 真实输出形态：URL 上色、token 片段 dim，中间夹一条重置序列。
        val logs = "  ${esc}[36mhttp://127.0.0.1:58627/${esc}[0m${esc}[2m#token=$token${esc}[0m"
        val endpoint = KimiWebEndpoint.parse(logs)
        assertEquals("http://127.0.0.1:58627", endpoint?.origin)
        assertEquals(token, endpoint?.token)
    }

    @Test
    fun parsesBannerSplitByAnsiEscapeBetweenLabelAndUrl() {
        val logs = "${esc}[32mOpen${esc}[0m ${esc}[34mhttp://127.0.0.1:58627/#token=$token${esc}[0m"
        assertEquals(token, KimiWebEndpoint.parse(logs)?.token)
    }

    @Test
    fun osciiTerminatorSequencesAreAlsoStripped() {
        // 部分终端会回显 OSC 8 超链接包裹，其终结符是 BEL 或 ESC \\。
        val url = "http://127.0.0.1:58627/#token=$token"
        val logs = "${esc}]8;;$url\u0007$url"
        assertEquals(token, KimiWebEndpoint.parse(logs)?.token)
    }

    @Test
    fun stripAnsiLeavesPlainTextUntouched() {
        val plain = "Kimi Code\n  http://127.0.0.1:58627/#token=$token\n"
        assertEquals(plain, KimiWebEndpoint.stripAnsi(plain))
    }

    @Test
    fun usesActualPortFromRetryWhenDefaultPortIsTaken() {
        // 58627 被占用时服务端向高位端口递增重试，必须采用日志里的真实端口。
        val endpoint = KimiWebEndpoint.parse("listening on http://127.0.0.1:58631/#token=$token")
        assertEquals("http://127.0.0.1:58631", endpoint?.origin)
    }

    @Test
    fun fallsBackToDefaultPortWhenUrlHasNoPort() {
        val endpoint = KimiWebEndpoint.parse("http://127.0.0.1/#token=$token")
        assertEquals("http://127.0.0.1:${KimiWebEndpoint.DEFAULT_PORT}", endpoint?.origin)
    }

    @Test
    fun rejectsOutOfRangePortAndFallsBack() {
        val endpoint = KimiWebEndpoint.parse("http://127.0.0.1:70000/#token=$token")
        assertEquals("http://127.0.0.1:${KimiWebEndpoint.DEFAULT_PORT}", endpoint?.origin)
    }

    @Test
    fun loopbackAliasesResolveToLoopbackOrigin() {
        // localhost / [::1] / 0.0.0.0 / :: 都要折算成手机侧可直接连通的回环地址。
        // IPv6 字面量尤其不能靠 substringBefore(':') 切分——首个冒号在字面量内部。
        listOf("localhost", "127.0.0.1", "[::1]", "0.0.0.0", "::").forEach { host ->
            val endpoint = KimiWebEndpoint.parse("http://$host:58627/#token=$token")
            assertEquals("host=$host 未折算到回环", "http://127.0.0.1:58627", endpoint?.origin)
        }
    }

    @Test
    fun annotatedTokenLineIsAcceptedWhenUrlFragmentMissing() {
        val logs = "Kimi Code\n  Local access: http://127.0.0.1:58627/\n  Token:    $token\n"
        val endpoint = KimiWebEndpoint.parse(logs)
        assertEquals("http://127.0.0.1:58627", endpoint?.origin)
        assertEquals(token, endpoint?.token)
    }

    @Test
    fun annotatedTokenLineSurvivesAnsiColouring() {
        val logs = "${esc}[1mToken:${esc}[0m    ${esc}[2m$token${esc}[0m"
        assertEquals(token, KimiWebEndpoint.parse(logs)?.token)
    }

    @Test
    fun urlWinsOverAnnotatedTokenLine() {
        val logs = "Token:    annotatedLineTokenValue\n  http://127.0.0.1:58627/#token=$token"
        assertEquals(token, KimiWebEndpoint.parse(logs)?.token)
    }

    @Test
    fun tokenOnlyLogsStillProduceConnectableEndpoint() {
        // URL 行已被尾部窗口挤出、只剩 Token 行时，不应判为启动失败：
        // 端口回退到默认值，仅凭 token 也能发起连接。
        val endpoint = KimiWebEndpoint.parse("Token:    $token")
        assertEquals("http://127.0.0.1:${KimiWebEndpoint.DEFAULT_PORT}", endpoint?.origin)
        assertEquals(token, endpoint?.token)
    }

    @Test
    fun returnsNullWhenNeitherUrlNorTokenPresent() {
        assertNull(KimiWebEndpoint.parse(null))
        assertNull(KimiWebEndpoint.parse(""))
        assertNull(KimiWebEndpoint.parse("   "))
        assertNull(KimiWebEndpoint.parse("Kimi Code is starting up..."))
        assertNull(KimiWebEndpoint.parse("${esc}[32mjust colors${esc}[0m"))
    }

    @Test
    fun parseTokenHandlesUrlsAndAnnotatedLines() {
        assertEquals(token, KimiWebEndpoint.parseToken("http://127.0.0.1:58627/#token=$token"))
        assertEquals(token, KimiWebEndpoint.parseToken("Token: $token"))
        assertEquals(token, KimiWebEndpoint.parseToken("token=\"$token\""))
        assertEquals("abc_-.~123", KimiWebEndpoint.parseToken("#token=abc_-.~123"))
        assertNull(KimiWebEndpoint.parseToken("no token here"))
    }

    @Test
    fun parseTokenRejectsShortNoiseValues() {
        // token 由 32 字节随机数编码而来，长度远超 8；
        // 短串多半是日志里的普通单词（如 "Token: none"），不能误当凭据。
        assertNull(KimiWebEndpoint.parseToken("Token: none"))
        assertNull(KimiWebEndpoint.parseToken("Token: abc"))
    }

    @Test
    fun queryStyleTokenIsAlsoRecognized() {
        assertEquals(token, KimiWebEndpoint.parseToken("http://127.0.0.1:58627/?token=$token"))
        assertEquals(token, KimiWebEndpoint.parseToken("http://127.0.0.1:58627/&token=$token"))
    }

    @Test
    fun apiBaseAndOpenableUrlAreDerivedCorrectly() {
        val endpoint = KimiWebEndpoint.parse("http://127.0.0.1:58627/#token=$token")!!
        assertEquals("http://127.0.0.1:58627/api/v1", endpoint.apiBase)
        assertEquals("http://127.0.0.1:58627/#token=$token", endpoint.openableUrl)
    }

    @Test
    fun openableUrlOmitsTokenFragmentWhenTokenMissing() {
        val endpoint = KimiWebEndpoint.parse("http://127.0.0.1:58627/")!!
        assertEquals("http://127.0.0.1:58627/", endpoint.openableUrl)
    }

    @Test
    fun parseOpenableUrlRequiresTokenSoBrowserLinkIsSelfAuthenticating() {
        // 没有 token 的地址打开只会看到 401，对用户是纯粹的困惑；
        // 这里返回 null，由调用方给出"启动失败"的明确反馈。
        assertEquals(
            "http://127.0.0.1:58627/#token=$token",
            KimiWebEndpoint.parseOpenableUrl("http://127.0.0.1:58627/#token=$token"),
        )
        assertNull(KimiWebEndpoint.parseOpenableUrl("http://127.0.0.1:58627/"))
        assertNull(KimiWebEndpoint.parseOpenableUrl(""))
        assertNull(KimiWebEndpoint.parseOpenableUrl(null))
    }

    @Test
    fun parsesBannerFromTailWindowThatTruncatedTheOpeningLine() {
        // readLogs 只回读尾部固定字节，请求日志会把横幅挤出窗口；
        // 此时只要尾部还残留地址就应解析成功，整段都是请求日志则判定为无端点
        // ——这正是"日志有内容但解析不到端点"的假失败场景。
        val tail = """
            |[12:00:01] POST /api/v1/sessions 200 12ms
            |[12:00:02] GET  /api/v1/sessions/s-1/status 200 3ms
        """.trimMargin()
        assertNull(KimiWebEndpoint.parse(tail))

        val tailWithAddress = tail + "\n  http://127.0.0.1:58627/#token=$token"
        assertEquals(token, KimiWebEndpoint.parse(tailWithAddress)?.token)
    }

    @Test
    fun unrelatedHostsNeverLeakIntoOrigin() {
        // 非回环主机一律不进 origin：客户端只连本机回环端口，
        // 免得把日志里出现的某个外部域名/文档链接当成服务端地址去拨号。
        val endpoint = KimiWebEndpoint.parse("http://docs.example.com:58627/#token=$token")
        assertEquals("http://127.0.0.1:${KimiWebEndpoint.DEFAULT_PORT}", endpoint?.origin)
    }

    @Test
    fun httpsLoopbackIsAcceptedAndNormalizedToHttp() {
        val endpoint = KimiWebEndpoint.parse("https://127.0.0.1:58627/#token=$token")
        assertEquals("http://127.0.0.1:58627", endpoint?.origin)
        assertTrue(endpoint?.apiBase?.endsWith("/api/v1") == true)
    }

    @Test
    fun endpointWithoutTokenIsParseableButNotYetReady() {
        // ensureEndpoint 要求 `endpoint != null && token 非空` 才算就绪；
        // 这里钉死"有地址无 token"时的行为，避免上层过早判定已就绪、
        // 拿着不能鉴权的地址发请求，结果得到一堆 401。
        val withoutToken = KimiWebEndpoint.parse("http://127.0.0.1:58627/")
        assertEquals("http://127.0.0.1:58627", withoutToken?.origin)
        assertNull(withoutToken?.token)

        val withToken = KimiWebEndpoint.parse("http://127.0.0.1:58627/\nToken: $token")
        assertEquals("http://127.0.0.1:58627", withToken?.origin)
        assertEquals(token, withToken?.token)
    }
}