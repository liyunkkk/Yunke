package io.github.mangi.eta.agent.kimi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Kimi 服务编排测试：找/拉起守护进程 → 解析端点 → 按 Eta 对话绑定复用会话。
 *
 * 这里覆盖的行为都直接对应现场故障：
 * 1. 已有健康的 `kimi web` 时必须复用，不能反复新起实例（否则端口递增、上下文全丢）；
 * 2. 端点一旦解析成功就缓存——`readLogs` 只读尾部固定字节，请求日志迟早把启动
 *    横幅挤出窗口，此时再解析就得到"有日志无端点"的假失败；
 * 3. 只有"URL + Token"齐备才算就绪，仅有地址不能拿去发请求；
 * 4. 会话按**对话**（而非工作目录）1:1 绑定，并落盘，进程回收后仍复用同一上下文。
 */
class KimiWebServiceTest {

    private lateinit var server: KimiTestHttpServer

    @Before
    fun setUp() {
        server = KimiTestHttpServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private val banner = "Kimi Code\n  http://127.0.0.1:58627/#token=dGhpcy1pcy1hLXRlc3QtdG9rZW4tZm9yLWtpbWk\n"

    @Test
    fun startsDaemonWhenNothingIsRunning() {
        val gateway = FakeGateway(logs = banner)
        val sleeps = mutableListOf<Long>()
        val service = KimiWebService(gateway, waitAttempts = 3, waitIntervalMs = 7L)

        val endpoint = service.ensureEndpoint { sleeps += it }

        assertNotNull(endpoint)
        assertEquals("http://127.0.0.1:58627", endpoint?.origin)
        assertEquals(1, gateway.starts)
        assertEquals(SupervisorKimiDaemonGateway.WEB_COMMAND, gateway.startedCommand)
        assertEquals(listOf("new"), gateway.startedTaskIds)
    }

    @Test
    fun reusesHealthyDaemonWithoutStartingAnother() {
        val gateway = FakeGateway(logs = banner)
            .also { it.addTask("old", running = true, command = SupervisorKimiDaemonGateway.WEB_COMMAND) }
        val service = KimiWebService(gateway, waitAttempts = 3, waitIntervalMs = 0L)

        val endpoint = service.ensureEndpoint {}

        assertNotNull(endpoint)
        // 关键：没有新起实例，且复用实例的日志被成功读到（一轮即拿到端点）。
        assertEquals(0, gateway.starts)
        assertEquals(1, gateway.logsCalls)
    }

    @Test
    fun legacyWebCommandIsAlsoTreatedAsReusable() {
        // 历史版本可能用 `kimi web`（带 --no-open）启动过；同样要能被复用，
        // 否则升级后会出现两个实例抢端口。
        val gateway = FakeGateway(logs = banner)
            .also { it.addTask("old", running = true, command = "kimi web") }
        val service = KimiWebService(gateway, waitAttempts = 3, waitIntervalMs = 0L)

        assertNotNull(service.ensureEndpoint {})
        assertEquals(0, gateway.starts)
    }

    @Test
    fun runningTasksWithUnrelatedCommandsDoNotCountAsKimiDaemon() {
        val gateway = FakeGateway(logs = banner)
            .also { it.addTask("other", running = true, command = "python server.py") }
        val service = KimiWebService(gateway, waitAttempts = 3, waitIntervalMs = 0L)

        assertNotNull(service.ensureEndpoint {})
        assertEquals(1, gateway.starts)
    }

    @Test
    fun stoppedKimiTaskIsNotReused() {
        val gateway = FakeGateway(logs = banner)
            .also { it.addTask("dead", running = false, command = SupervisorKimiDaemonGateway.WEB_COMMAND) }
        val service = KimiWebService(gateway, waitAttempts = 3, waitIntervalMs = 0L)

        assertNotNull(service.ensureEndpoint {})
        assertEquals(1, gateway.starts)
    }

    @Test
    fun returnsNullWhenStartFails() {
        val gateway = FakeGateway(logs = banner, startSucceeds = false)
        val service = KimiWebService(gateway, waitAttempts = 3, waitIntervalMs = 0L)

        assertNull(service.ensureEndpoint {})
        assertEquals(1, gateway.starts)
    }

    @Test
    fun newDaemonThatNeverPrintsAnAddressFailsAfterBudget() {
        // 旧实例已退出（running=false）→ 必须新起一个；若新实例迟迟不打印地址，
        // 预算耗尽后应老实返回 null，而不是给上层一个空端点。
        val gateway = FakeGateway(logs = "starting...")
            .also { it.addTask("old", running = false, command = SupervisorKimiDaemonGateway.WEB_COMMAND) }
        val service = KimiWebService(gateway, waitAttempts = 5, waitIntervalMs = 0L)

        assertNull(service.ensureEndpoint {})
        assertEquals(1, gateway.starts)
    }

    @Test
    fun returnsNullAfterWaitBudgetExhaustedWithoutEndpoint() {
        val gateway = FakeGateway(logs = "still booting, no address yet")
        val sleeps = mutableListOf<Long>()
        val service = KimiWebService(gateway, waitAttempts = 4, waitIntervalMs = 11L)

        assertNull(service.ensureEndpoint { sleeps += it })

        // 必须通过 sleep 主动让出等待（而非忙轮询），且次数与预算严格一致。
        assertEquals(listOf(11L, 11L, 11L, 11L), sleeps)
    }

    @Test
    fun addressWithoutTokenIsNotConsideredReady() {
        // 只有地址没有 token 时发请求只会拿到 401，必须继续等到 token
        // 出现或预算耗尽，不能提前判定"已就绪"。
        val gateway = FakeGateway(logs = "http://127.0.0.1:58627/")
        val service = KimiWebService(gateway, waitAttempts = 2, waitIntervalMs = 0L)

        assertNull(service.ensureEndpoint {})
    }

    @Test
    fun returnsNullWhenLogsAreUnavailable() {
        val gateway = FakeGateway(logs = "")
        val service = KimiWebService(gateway, waitAttempts = 2, waitIntervalMs = 0L)

        assertNull(service.ensureEndpoint {})
        assertNull(service.cachedEndpointOrNull())
    }

    @Test
    fun endpointIsCachedSoTailWindowEvictionCannotCauseFalseFailure() {
        val gateway = FakeGateway(logs = banner)
        val service = KimiWebService(gateway, waitAttempts = 3, waitIntervalMs = 0L)

        val first = service.ensureEndpoint {}
        assertNotNull(first)
        val listCallsAfterFirst = gateway.lists

        // 模拟启动横幅被请求日志挤出尾部窗口。
        gateway.logs = "[12:00:03] GET /api/v1/sessions/s-1/status 200 3ms"
        val second = service.ensureEndpoint {}

        assertEquals(first, second)
        assertEquals(1, gateway.starts)
        assertEquals("缓存命中后不应再读日志/列表", listCallsAfterFirst, gateway.lists)
    }

    @Test
    fun forgetEndpointForcesReparseAndReturnsNullWhileLogsAreStillStale() {
        val gateway = FakeGateway(logs = banner)
        val service = KimiWebService(gateway, waitAttempts = 2, waitIntervalMs = 0L)

        assertNotNull(service.ensureEndpoint {})
        service.forgetEndpoint()
        assertNull(service.cachedEndpointOrNull())

        // 服务端重启导致旧端点失效：清缓存后若日志还没打印新横幅，应老实返回 null，
        // 而不是把死地址继续交给上层去连接。
        gateway.logs = "restarting..."
        assertNull(service.ensureEndpoint {})

        gateway.logs = "http://127.0.0.1:58631/#token=dGhpcy1pcy1hLXRlc3QtdG9rZW4tZm9yLWtpbWk"
        assertEquals("http://127.0.0.1:58631", service.ensureEndpoint {}?.origin)
    }

    @Test
    fun sessionIsCreatedOncePerConversationThenReused() {
        val gateway = FakeGateway(logs = banner)
        val service = KimiWebService(gateway, waitAttempts = 2, waitIntervalMs = 0L)
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w","metadata":{"cwd":"/workspace/a"}}""")
        val client = KimiWebApiClient(server.origin, "token")

        val first = service.sessionFor(client, "/workspace/a", "conv-1")
        val second = service.sessionFor(client, "/workspace/a", "conv-1")

        assertEquals("s-1", first)
        assertEquals(first, second)
        // 第二次必须走缓存：同一对话的多轮委派要共享 Kimi 上下文。
        assertEquals(1, server.requestCount())
        assertEquals(1, service.cachedSessionCount())
    }

    @Test
    fun sameConversationReusesSessionEvenWhenProjectPathChanges() {
        // 绑定键是对话而不是目录：中途换项目目录也必须继续同一个 Kimi 会话，
        // 否则「先问整体方案、再让子代理去另一个仓库改代码」会丢掉前文。
        val gateway = FakeGateway(logs = banner)
        val service = KimiWebService(gateway, waitAttempts = 2, waitIntervalMs = 0L)
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w","metadata":{"cwd":"/workspace/a"}}""")
        val client = KimiWebApiClient(server.origin, "token")

        assertEquals("s-1", service.sessionFor(client, "/workspace/a", "conv-1"))
        assertEquals("s-1", service.sessionFor(client, "/workspace/b", "conv-1"))
        assertEquals(1, server.requestCount())
    }

    @Test
    fun bindingSurvivesProcessRecycleThroughPersistedStore() {
        // 落盘 store 被新实例复用：模拟进程被回收后重新委派，
        // 必须命中旧绑定而不是新建会话（否则上下文散乱重建）。
        val store = InMemoryKimiSessionBindingStore()
        val gateway = FakeGateway(logs = banner)
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w","metadata":{"cwd":"/workspace/a"}}""")
        val client = KimiWebApiClient(server.origin, "token")

        val first = KimiWebService(gateway, bindingStore = store, waitAttempts = 2, waitIntervalMs = 0L)
        assertEquals("s-1", first.sessionFor(client, "/workspace/a", "conv-1"))
        assertEquals(1, server.requestCount())

        val recycled = KimiWebService(gateway, bindingStore = store, waitAttempts = 2, waitIntervalMs = 0L)
        assertEquals("s-1", recycled.sessionFor(client, "/workspace/a", "conv-1"))
        assertEquals(1, server.requestCount())
        assertEquals("s-1", recycled.boundSessionId("conv-1"))
    }

    @Test
    fun differentConversationsGetDifferentSessions() {
        val gateway = FakeGateway(logs = banner)
        val service = KimiWebService(gateway, waitAttempts = 2, waitIntervalMs = 0L)
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w","metadata":{"cwd":"/workspace/a"}}""")
        server.enqueueEnvelope("""{"id":"s-2","workspace_id":"w","metadata":{"cwd":"/workspace/a"}}""")
        val client = KimiWebApiClient(server.origin, "token")

        assertEquals("s-1", service.sessionFor(client, "/workspace/a", "conv-1"))
        assertEquals("s-2", service.sessionFor(client, "/workspace/a", "conv-2"))
        assertEquals(2, service.cachedSessionCount())
    }

    @Test
    fun forgetSessionDropsOnlyThatConversationSoNextCallRecreates() {
        val gateway = FakeGateway(logs = banner)
        val service = KimiWebService(gateway, waitAttempts = 2, waitIntervalMs = 0L)
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w","metadata":{"cwd":"/workspace/a"}}""")
        server.enqueueEnvelope("""{"id":"s-3","workspace_id":"w","metadata":{"cwd":"/workspace/a"}}""")
        server.enqueueEnvelope("""{"id":"s-2","workspace_id":"w","metadata":{"cwd":"/workspace/a"}}""")
        val client = KimiWebApiClient(server.origin, "token")

        service.sessionFor(client, "/workspace/a", "conv-1")
        service.sessionFor(client, "/workspace/a", "conv-2")
        service.forgetSession("conv-1")

        assertEquals(1, service.cachedSessionCount())
        // 未被 forget 的对话仍复用，被 forget 的重开一轮（服务端重启后 sessionId 失效的场景）。
        assertEquals("s-3", service.sessionFor(client, "/workspace/a", "conv-2"))
        assertEquals("s-2", service.sessionFor(client, "/workspace/a", "conv-1"))
        assertEquals(3, server.requestCount())
        assertNull(service.boundSessionId("missing"))
    }

    @Test
    fun clientForUsesInjectedFactory() {
        val gateway = FakeGateway(logs = banner)
        var built: Pair<String, String?>? = null
        val service = KimiWebService(
            daemon = gateway,
            clientFactory = { origin, token ->
                built = origin to token
                KimiWebApiClient(server.origin, token)
            },
            waitAttempts = 1,
            waitIntervalMs = 0L,
        )

        val endpoint = service.ensureEndpoint {}
        service.clientFor(endpoint!!)

        assertEquals("http://127.0.0.1:58627" to "dGhpcy1pcy1hLXRlc3QtdG9rZW4tZm9yLWtpbWk", built)
    }

    @Test
    fun sessionTitleFallsBackToWorkspaceFolderName() {
        val gateway = FakeGateway(logs = banner)
        val service = KimiWebService(gateway, waitAttempts = 1, waitIntervalMs = 0L)
        server.enqueueEnvelope("""{"id":"s-1","workspace_id":"w","metadata":{"cwd":"/workspace/demo"}}""")

        service.sessionFor(KimiWebApiClient(server.origin, "token"), "/workspace/demo", "conv-1")

        val body = org.json.JSONObject(server.request(0).body)
        assertEquals("demo", body.getString("title"))
    }

    /** 只实现 [KimiDaemonGateway] 的三个方法，用可变状态驱动各种时序。 */
    private class FakeGateway(
        var logs: String,
        private val startSucceeds: Boolean = true,
    ) : KimiDaemonGateway {

        private val tasks = mutableListOf<KimiDaemonTask>()
        var starts = 0
        var lists = 0
        var logsCalls = 0
        var startedCommand: String? = null
        val startedTaskIds = mutableListOf<String>()

        fun addTask(taskId: String, running: Boolean, command: String) {
            tasks += KimiDaemonTask(taskId, running, command)
        }

        override fun list(): List<KimiDaemonTask> {
            lists++
            return tasks.toList()
        }

        override fun start(): String? {
            starts++
            if (!startSucceeds) return null
            startedCommand = SupervisorKimiDaemonGateway.WEB_COMMAND
            tasks += KimiDaemonTask("new", true, SupervisorKimiDaemonGateway.WEB_COMMAND)
            startedTaskIds += "new"
            return "new"
        }

        override fun logs(taskId: String): String? {
            logsCalls++
            return logs.ifBlank { null }
        }
    }
}