package io.github.mangi.eta.agent.kimi

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Kimi 本机 Web REST 契约测试。
 *
 * 客户端与 `kap-server` 之间只有一层 JSON 信封（`{code,msg,data,request_id}`，
 * `code==0` 为成功），字段名一旦写错不会有编译期保护，只会运行时静默拿到空值。
 * 因此这里逐个钉死请求形状与响应解析：
 * - 路径必须带 `/api/v1` 前缀；
 * - 鉴权头必须是 `Authorization: Bearer <token>`；
 * - `data` 信封里字段名与 `KimiSession` / `KimiMessage` / `KimiSessionStatus` 的映射；
 * - 附件 content 片段的线格式（图片 base64/path、文件 path）；
 * - `GET /config` 与 `GET /sessions/{id}` 的解析——前者是「下发哪个模型」的唯一来源，
 *   后者是「本轮到底成功没有」的唯一来源。
 */
class KimiWebApiClientTest {

    private lateinit var server: KimiTestHttpServer

    @Before
    fun setUp() {
        server = KimiTestHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(token: String? = "token-abc"): KimiWebApiClient =
        KimiWebApiClient(server.origin, token)

    @Test
    fun createSessionPostsToApiV1WithBearerTokenAndCwdMetadata() {
        server.enqueueEnvelope(
            """{"id":"s-1","workspace_id":"w-1","title":"workspace",
               "metadata":{"cwd":"/workspace/project"}}""".trimIndent(),
        )

        val session = client().createSession("/workspace/project", title = "project")

        assertEquals("s-1", session.id)
        assertEquals("w-1", session.workspaceId)
        assertEquals("workspace", session.title)
        assertEquals("/workspace/project", session.cwd)

        val recorded = server.request(0)
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/sessions", recorded.path)
        assertEquals("Bearer token-abc", recorded.header("authorization"))
        val body = JSONObject(recorded.body)
        assertEquals("/workspace/project", body.getJSONObject("metadata").getString("cwd"))
        assertEquals("project", body.getString("title"))
    }

    @Test
    fun createSessionOmitsBlankTitle() {
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w-1","metadata":{"cwd":"/w"}}""")
        client().createSession("/w")
        val body = JSONObject(server.request(0).body)
        assertFalse("空白 title 不应写入请求体", body.has("title"))
    }

    @Test
    fun authorizationHeaderIsAbsentWhenTokenIsNullOrBlank() {
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w-1","metadata":{"cwd":"/w"}}""")
        client(token = null).createSession("/w")
        assertNull(server.request(0).header("authorization"))

        server.enqueueEnvelope("""{"id":"s-2","workspace_id":"w-1","metadata":{"cwd":"/w"}}""")
        client(token = "").createSession("/w")
        assertNull(server.request(1).header("authorization"))
    }

    @Test
    fun submitPromptEncodesTextAsContentArray() {
        server.enqueueEnvelope("""{"prompt_id":"p-1","user_message_id":"m-1","status":"queued"}""")

        val prompt = client().submitPrompt("s-1", "修复构建失败")

        assertEquals("p-1", prompt.promptId)
        assertEquals("m-1", prompt.userMessageId)
        assertEquals("queued", prompt.status)

        val recorded = server.request(0)
        assertEquals("/api/v1/sessions/s-1/prompts", recorded.path)
        val content = JSONObject(recorded.body).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("修复构建失败", content.getJSONObject(0).getString("text"))
    }

    @Test
    fun submitPromptStillProducesContentArrayWhenTextIsBlank() {
        // 服务端要求 content 非空；仅附件（或完全空白）时也要给出可解析的数组。
        server.enqueueEnvelope("""{"prompt_id":"p-1","user_message_id":"m-1","status":"queued"}""")
        client().submitPrompt("s-1", "   ")
        val content = JSONObject(server.request(0).body).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("", content.getJSONObject(0).getString("text"))
    }

    @Test
    fun imageBase64AttachmentIsInlinedWithMediaTypeAndName() {
        server.enqueueEnvelope("""{"prompt_id":"p-1","user_message_id":"m-1","status":"queued"}""")

        client().submitPrompt(
            sessionId = "s-1",
            text = "看这张图",
            attachments = listOf(
                KimiAttachment.ImageBase64(
                    mediaType = "image/png",
                    base64Data = "iVBORw0KGgo=",
                    name = "screenshot.png",
                ),
            ),
        )

        val content = JSONObject(server.request(0).body).getJSONArray("content")
        assertEquals(2, content.length())
        val image = content.getJSONObject(1)
        assertEquals("image", image.getString("type"))
        assertEquals("screenshot.png", image.getString("name"))
        val source = image.getJSONObject("source")
        assertEquals("base64", source.getString("kind"))
        assertEquals("image/png", source.getString("media_type"))
        assertEquals("iVBORw0KGgo=", source.getString("data"))
    }

    @Test
    fun imagePathAttachmentUsesPathSource() {
        server.enqueueEnvelope("""{"prompt_id":"p-1","user_message_id":"m-1","status":"queued"}""")
        client().submitPrompt(
            sessionId = "s-1",
            text = "",
            attachments = listOf(KimiAttachment.ImagePath("/workspace/a.png")),
        )
        val image = JSONObject(server.request(0).body).getJSONArray("content").getJSONObject(0)
        assertEquals("image", image.getString("type"))
        assertEquals("path", image.getJSONObject("source").getString("kind"))
        assertEquals("/workspace/a.png", image.getJSONObject("source").getString("path"))
    }

    @Test
    fun fileAttachmentUsesFlatPathAndOptionalMediaType() {
        server.enqueueEnvelope("""{"prompt_id":"p-1","user_message_id":"m-1","status":"queued"}""")
        client().submitPrompt(
            sessionId = "s-1",
            text = "参考这个文件",
            attachments = listOf(
                KimiAttachment.File(
                    path = "/workspace/build.gradle.kts",
                    name = "build.gradle.kts",
                    mediaType = "text/plain",
                ),
            ),
        )
        val file = JSONObject(server.request(0).body).getJSONArray("content").getJSONObject(1)
        assertEquals("file", file.getString("type"))
        assertEquals("/workspace/build.gradle.kts", file.getString("path"))
        assertEquals("build.gradle.kts", file.getString("name"))
        assertEquals("text/plain", file.getString("media_type"))
    }

    @Test
    fun fileAttachmentOmitsBlankOptionalFields() {
        server.enqueueEnvelope("""{"prompt_id":"p-1","user_message_id":"m-1","status":"queued"}""")
        client().submitPrompt("s-1", "x", listOf(KimiAttachment.File(path = "/w/a.txt", name = " ", mediaType = "")))
        val file = JSONObject(server.request(0).body).getJSONArray("content").getJSONObject(1)
        assertFalse(file.has("name"))
        assertFalse(file.has("media_type"))
    }

    @Test
    fun submitPromptForwardsOptionalModelAndPermissionMode() {
        server.enqueueEnvelope("""{"prompt_id":"p-1","user_message_id":"m-1","status":"queued"}""")
        client().submitPrompt("s-1", "x", model = "kimi-k2", permissionMode = "auto")
        val body = JSONObject(server.request(0).body)
        assertEquals("kimi-k2", body.getString("model"))
        assertEquals("auto", body.getString("permission_mode"))
    }

    @Test
    fun sessionIdIsUrlEncodedSoSlashOrSpaceCannotBreakThePath() {
        server.enqueueEnvelope("""{"busy":false}""")
        client().sessionStatus("s 1/2")
        val path = server.request(0).path
        assertTrue(path.startsWith("/api/v1/sessions/"))
        assertTrue(path.endsWith("/status"))
        // 会话 id 内部的 '/' 必须已被转义，不能被当成路径分隔符拆出额外一段。
        assertFalse(path.contains("/s 1/2/"))
        assertTrue(path.lowercase().contains("%2f"))
    }

    @Test
    fun sessionStatusParsesBusyAndTokenBudget() {
        server.enqueueEnvelope(
            """{"busy":true,"model":"kimi-k2","context_tokens":1234,"max_context_tokens":200000}""",
        )
        val status = client().sessionStatus("s-1")
        assertTrue(status.busy)
        assertEquals("kimi-k2", status.model)
        assertEquals(1234, status.contextTokens)
        assertEquals(200000, status.maxContextTokens)
        assertEquals("/api/v1/sessions/s-1/status", server.request(0).path)
    }

    @Test
    fun sessionStatusDefaultsToNotBusyWhenFieldMissing() {
        // `busy` 缺省必须视为"已结束"，否则轮询会一直等下去直到超时。
        server.enqueueEnvelope("""{}""")
        val status = client().sessionStatus("s-1")
        assertFalse(status.busy)
        assertEquals(null, status.model)
        assertEquals(0, status.contextTokens)
    }

    @Test
    fun listMessagesKeepsTextPartsAndDropsThinking() {
        server.enqueueEnvelope(
            """
            {"items":[
              {"id":"m-1","role":"user","content":[{"type":"text","text":"请修复"}]},
              {"id":"m-2","role":"assistant","content":[
                 {"type":"thinking","thinking":"内部推理"},
                 {"type":"text","text":"我先看构建脚本。"},
                 {"type":"tool_use","name":"read_file"}
              ]},
              {"id":"m-3","role":"assistant","content":[{"type":"thinking","thinking":"只有思考"}]}
            ]}
            """.trimIndent(),
        )

        val messages = client().listMessages("s-1")

        assertEquals(listOf("m-1", "m-2", "m-3"), messages.map { it.id })
        assertEquals(listOf("user", "assistant", "assistant"), messages.map { it.role })
        assertEquals("请修复", messages[0].text)
        assertEquals("我先看构建脚本。", messages[1].text)
        // thinking / tool_use 片段不进入 text，避免把内部推理当答复回传。
        assertEquals("", messages[2].text)
        assertEquals("/api/v1/sessions/s-1/messages?limit=20", server.request(0).path)
    }

    @Test
    fun listMessagesSendsLimitAndBeforeIdCursor() {
        server.enqueueEnvelope("""{"items":[]}""")
        val messages = client().listMessages("s-1", limit = 5, beforeId = "m-9")
        assertEquals(emptyList<KimiMessage>(), messages)
        assertEquals("/api/v1/sessions/s-1/messages?limit=5&before_id=m-9", server.request(0).path)
    }

    @Test
    fun listMessagesToleratesMissingItemsArray() {
        server.enqueueEnvelope("""{}""")
        assertEquals(emptyList<KimiMessage>(), client().listMessages("s-1"))
    }

    @Test
    fun abortPostsToPromptsAbort() {
        server.enqueueEnvelope("""{"aborted":true}""")
        assertTrue(client().abort("s-1"))
        val recorded = server.request(0)
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/sessions/s-1/prompts/abort", recorded.path)
    }

    @Test
    fun nonZeroEnvelopeCodeBecomesTypedApiException() {
        server.enqueueEnvelope("""null""", code = 40401, msg = "session not found")

        val failure = runCatching { client().sessionStatus("s-1") }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is KimiWebApiException)
        assertEquals("40401", (failure as KimiWebApiException).code)
        assertEquals("session not found", failure.message)
    }

    @Test
    fun httpErrorCarriesStatusCodeAndMessageFromBody() {
        server.enqueue(404, """{"code":40401,"msg":"session not found","data":null}""")

        val failure = runCatching { client().sessionStatus("gone") }.exceptionOrNull()

        assertTrue(failure is KimiWebApiException)
        // 上层据此判断"会话已失效"并自动重开会话，因此 code 必须稳定可辨。
        assertEquals("HTTP_404", (failure as KimiWebApiException).code)
        assertEquals("session not found", failure.message)
    }

    @Test
    fun httpErrorFallsBackToStatusCodeWhenBodyHasNoMessage() {
        server.enqueue(500, "boom, not json")
        val failure = runCatching { client().sessionStatus("s-1") }.exceptionOrNull()
        assertEquals("HTTP_500", (failure as KimiWebApiException).code)
        assertEquals("HTTP 500", failure.message)
    }

    @Test
    fun nonJsonSuccessfulBodyIsRejectedAsInvalidResponse() {
        // 反向代理出错时可能回一页 HTML；静默当成空 data 会让上层误判"已完成"。
        server.enqueue(200, "<html>Bad Gateway</html>")
        val failure = runCatching { client().sessionStatus("s-1") }.exceptionOrNull()
        assertEquals("INVALID_RESPONSE", (failure as KimiWebApiException).code)
    }

    @Test
    fun missingDataFieldYieldsEmptyObjectInsteadOfCrashing() {
        server.enqueue(200, """{"code":0,"msg":"ok"}""")
        val status = client().sessionStatus("s-1")
        assertFalse(status.busy)
    }

    @Test
    fun requestReuseSurvivesSequentialCallsBecauseConnectionIsClosedEachTime() {
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w","metadata":{"cwd":"/w"}}""")
        server.enqueueEnvelope("""{"prompt_id":"p","user_message_id":"m","status":"queued"}""")
        server.enqueueEnvelope("""{"busy":false}""")

        val client = client()
        client.createSession("/w")
        client.submitPrompt("s-1", "hi")
        assertFalse(client.sessionStatus("s-1").busy)
        assertEquals(3, server.requestCount())
        assertEquals(
            listOf("/api/v1/sessions", "/api/v1/sessions/s-1/prompts", "/api/v1/sessions/s-1/status"),
            server.requests().map { it.path },
        )
    }

    @Test
    fun defaultModelReadsTopLevelConfigField() {
        // 容器内 config.toml 的 default_model 就是靠这条 REST 通路取到的：
        // rootfs 里的文件是 600 root:root，App 进程直读会失败。
        server.enqueueEnvelope("""{"default_model":"eta-wb2/cn:deepseek-v4.1-flash","providers":{}}""")

        assertEquals("eta-wb2/cn:deepseek-v4.1-flash", client().defaultModel())
        assertEquals("/api/v1/config", server.request(0).path)
    }

    @Test
    fun defaultModelIsNullWhenConfigHasNoModel() {
        server.enqueueEnvelope("""{"providers":{}}""")

        assertNull(client().defaultModel())
    }

    @Test
    fun defaultModelIsNullWhenConfigEndpointFails() {
        // 读不到配置不能让委派直接崩：上层会退化为「不带模型提交」，
        // 失败原因由 turn 结果如实上报。
        server.enqueue(500, """{"code":500,"msg":"boom","data":{}}""")

        assertNull(client().defaultModel())
    }

    @Test
    fun sessionSummaryReadsLastTurnReasonAndModel() {
        server.enqueueEnvelope(
            """{"id":"s-1","workspace_id":"w","last_turn_reason":"failed",""" +
                """"agent_config":{"model":"kimi-k2"}}"""
        )

        val summary = client().sessionSummary("s-1")

        assertEquals("s-1", summary.id)
        assertEquals("failed", summary.lastTurnReason)
        assertEquals("kimi-k2", summary.model)
        assertTrue(summary.failed)
        assertEquals("/api/v1/sessions/s-1", server.request(0).path)
    }

    @Test
    fun sessionSummaryCompletedTurnIsNotFailure() {
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w","last_turn_reason":"completed"}""")

        val summary = client().sessionSummary("s-1")

        assertEquals("completed", summary.lastTurnReason)
        assertFalse(summary.failed)
        assertNull(summary.model)
    }

    @Test
    fun sessionSummaryWithoutReasonIsNotFailure() {
        // 从未执行过的会话没有 last_turn_reason：不能当成失败。
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w"}""")

        assertNull(client().sessionSummary("s-1").lastTurnReason)
        assertFalse(client().sessionSummary("s-1").failed)
    }

}

/**
 * 答复抽取规则：从后往前取最后一条非空 assistant 文本。
 *
 * Kimi 会在会话里追加 `Acknowledged.` 一类的确认消息与纯工具调用轮次，
 * 取"最后一条文本"才能拿到真正的交付说明。
 */
class KimiReplyExtractorTest {

    @Test
    fun picksLastNonBlankAssistantText() {
        val messages = listOf(
            KimiMessage("m-1", "user", "请修复构建失败"),
            KimiMessage("m-2", "assistant", "我先看看。"),
            KimiMessage("m-3", "assistant", "已修复 gradle 依赖。"),
        )
        assertEquals("已修复 gradle 依赖。", KimiReplyExtractor.extract(messages))
    }

    @Test
    fun skipsTrailingToolOnlyAssistantTurns() {
        // 工具调用轮次在解析阶段 text 为空，不能因此把答复判定为"缺失"。
        val messages = listOf(
            KimiMessage("m-1", "assistant", "改动完成。"),
            KimiMessage("m-2", "assistant", ""),
            KimiMessage("m-3", "assistant", "   "),
        )
        assertEquals("改动完成。", KimiReplyExtractor.extract(messages))
    }

    @Test
    fun ignoresUserMessagesEvenWhenTheyComeLast() {
        val messages = listOf(
            KimiMessage("m-1", "assistant", "答复"),
            KimiMessage("m-2", "user", "追加提问"),
        )
        assertEquals("答复", KimiReplyExtractor.extract(messages))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("答复", KimiReplyExtractor.extract(listOf(KimiMessage("m", "assistant", "\n  答复 \n"))))
    }

    @Test
    fun degradesToExplanationWhenNoAssistantTextExists() {
        val reply = KimiReplyExtractor.extract(
            listOf(KimiMessage("m-1", "user", "提问"), KimiMessage("m-2", "assistant", "")),
        )
        assertTrue(reply.isNotBlank())
        assertTrue(reply.contains("未返回文本答复"))
    }

    @Test
    fun emptyMessageListAlsoDegradesGracefully() {
        assertTrue(KimiReplyExtractor.extract(emptyList()).isNotBlank())
    }
}

/**
 * 子代理结果 JSON 契约。
 *
 * 这份 JSON 是主智能体唯一能看到的交付物：字段名与截断长度都属于对外契约，
 * 一旦漂移，主智能体会拿到"成功但内容为空"的结果而不自知。
 */
class KimiSubagentOutcomeTest {

    @Test
    fun successPayloadCarriesGitEvidenceAndSessionId() {
        val json = JSONObject(
            KimiSubagentOutcome(
                ok = true,
                task = "修复构建",
                projectPath = "/workspace/project",
                sessionId = "s-1",
                content = "已修复。",
                gitModifiedFiles = "M app/build.gradle.kts",
                gitDiffStat = "1 file changed, 1 insertion(+)",
            ).toJson(),
        )

        assertTrue(json.getBoolean("ok"))
        assertEquals("delegate_to_kimi_code", json.getString("tool"))
        assertEquals("修复构建", json.getString("task"))
        assertEquals("/workspace/project", json.getString("project_path"))
        assertEquals("s-1", json.getString("session_id"))
        assertEquals("已修复。", json.getString("output"))
        assertEquals("M app/build.gradle.kts", json.getString("git_modified_files"))
        assertEquals("1 file changed, 1 insertion(+)", json.getString("git_diff_stat"))
        assertTrue(json.getString("message").isNotBlank())
        assertFalse("成功时不应带错误码", json.has("code"))
    }

    @Test
    fun blankGitEvidenceBecomesExplicitHumanReadableText() {
        // 空字符串对主智能体是不可区分的噪声，必须换成明确的"没有变更"表述。
        val json = JSONObject(
            KimiSubagentOutcome(
                ok = true,
                task = "t",
                projectPath = "/w",
                sessionId = null,
                content = "c",
                gitModifiedFiles = "",
                gitDiffStat = "   ",
            ).toJson(),
        )
        assertEquals("无 git 变更或非 git 仓库", json.getString("git_modified_files"))
        assertEquals("无代码增删差异", json.getString("git_diff_stat"))
        assertFalse(json.has("session_id"))
    }

    @Test
    fun failurePayloadCarriesCodeAndMessage() {
        val json = JSONObject(
            KimiSubagentOutcome(
                ok = false,
                task = "t",
                projectPath = "/w",
                sessionId = null,
                content = "",
                gitModifiedFiles = "",
                gitDiffStat = "",
                errorCode = "TIMEOUT",
                errorMessage = "超出预算",
            ).toJson(),
        )
        assertFalse(json.getBoolean("ok"))
        assertEquals("TIMEOUT", json.getString("code"))
        assertTrue(json.getString("message").contains("超出预算"))
    }

    @Test
    fun failureWithoutExplicitCodeFallsBackToGenericCode() {
        val json = JSONObject(
            KimiSubagentOutcome(
                ok = false,
                task = "t",
                projectPath = "/w",
                sessionId = null,
                content = "",
                gitModifiedFiles = "",
                gitDiffStat = "",
            ).toJson(),
        )
        assertEquals("SUBAGENT_FAILED", json.getString("code"))
    }

    @Test
    fun outputIsTruncatedFromTheHeadSoLatestDeliveryNotesSurvive() {
        // 长答复必须保留尾部：真正的"改动说明 + 遗留点"在末尾，
        // 截头留尾才能让主智能体看到结论而不是开场白。
        val long = "A".repeat(5000) + "TAIL-MARKER"
        val json = JSONObject(
            KimiSubagentOutcome(
                ok = true,
                task = "t",
                projectPath = "/w",
                sessionId = null,
                content = long,
                gitModifiedFiles = "",
                gitDiffStat = "",
            ).toJson(),
        )
        val output = json.getString("output")
        assertTrue(output.length <= 4000)
        assertTrue(output.endsWith("TAIL-MARKER"))
        assertFalse(output.startsWith("A".repeat(4000)))
    }

    @Test
    fun jsonIsAlwaysWellFormedForExtremeInputs() {
        // 引号、换行、反斜杠都必须被正确转义，否则主智能体解析工具结果会直接失败。
        val outcome = KimiSubagentOutcome(
            ok = true,
            task = "含\"引号\"与\\反斜杠\n换行",
            projectPath = "/w",
            sessionId = "s",
            content = "换行\n与\"引号\"",
            gitModifiedFiles = "M\tx",
            gitDiffStat = "-",
        )
        val json = JSONObject(outcome.toJson())
        assertEquals(outcome.task, json.getString("task"))
        assertEquals(outcome.content, json.getString("output"))
    }
}
