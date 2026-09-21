package io.github.mangi.eta.agent.kimi

import java.util.concurrent.ConcurrentHashMap

/**
 * Kimi 本机服务与守护进程交互的最小抽象。
 *
 * 抽成接口后，[KimiWebService] 的会话编排逻辑可以在单元测试里
 * 用假实现驱动，不必真的启动 Node 运行时。
 */
internal interface KimiDaemonGateway {

    /** 当前登记的守护任务，元素为 (taskId, running, command)。 */
    fun list(): List<KimiDaemonTask>

    /** 启动 `kimi web --no-open`，成功返回新任务 id。 */
    fun start(): String?

    /** 读取任务日志；失败返回 null。 */
    fun logs(taskId: String): String?
}

internal data class KimiDaemonTask(
    val taskId: String,
    val running: Boolean,
    val command: String,
)

/**
 * Kimi Web 服务编排：负责"找到或拉起服务端 → 解析端点 → 复用会话"。
 *
 * 之所以不让每次工具调用都重新启动 `kimi web`，是因为 Node 冷启动本身
 * 就要数秒，且多实例会让端口不断递增、旧会话上下文全部丢失。这里按
 * **Eta 对话**绑定 sessionId（而非按工作目录），让同一个对话的多轮委派
 * 始终复用同一个 Kimi 会话持续推进，不会因为换了项目目录就散乱新建。
 *
 * 绑定关系会落盘（[KimiSessionBindingStore]），进程被回收后依然有效。
 */
internal class KimiWebService(
    private val daemon: KimiDaemonGateway,
    private val clientFactory: (origin: String, token: String?) -> KimiWebApiClient = { origin, token ->
        KimiWebApiClient(origin, token)
    },
    private val bindingStore: KimiSessionBindingStore = InMemoryKimiSessionBindingStore(),
    private val waitAttempts: Int = 40,
    private val waitIntervalMs: Long = 500,
) {

    /** 对话 id → Kimi sessionId；写入时同步落盘，保证跨进程存活。 */
    private val sessionBindings = ConcurrentHashMap<String, String>()

    init {
        sessionBindings.putAll(bindingStore.load())
    }

    /**
     * 已解析成功的端点缓存。
     *
     * 守护任务日志会不断追加（Kimi 每个请求都写日志行），而 `readLogs` 只回读
     * 尾部固定字节，启动横幅迟早会被挤出窗口——此时再解析就会得到「有日志但无
     * 端点」的假失败。因此首次成功后缓存下来，后续委派直接复用。
     */
    private var cachedEndpoint: KimiWebEndpoint.Endpoint? = null

    /**
     * 确保本机服务端可用并返回鉴权端点。
     *
     * 必须通过 [sleep] 主动让出等待（而非忙轮询），因为 Node 启动期间
     * 端口尚未监听，任何提前的 REST 调用都会直接连接失败。
     */
    fun ensureEndpoint(sleep: (Long) -> Unit): KimiWebEndpoint.Endpoint? {
        cachedEndpoint?.let { return it }

        var taskId = daemon.list()
            .firstOrNull { it.running && it.command.trim() in WEB_COMMANDS }
            ?.taskId

        if (taskId == null) {
            taskId = daemon.start() ?: return null
        }

        repeat(waitAttempts) {
            val task = daemon.list().firstOrNull { it.taskId == taskId }
            if (task == null || !task.running) return null
            val endpoint = daemon.logs(taskId)?.let(KimiWebEndpoint::parse)
            if (endpoint != null && !endpoint.token.isNullOrBlank()) {
                cachedEndpoint = endpoint
                return endpoint
            }
            sleep(waitIntervalMs)
        }
        return null
    }

    /** 服务端重启或连接被拒时丢弃缓存，让下一次调用重新解析。 */
    fun forgetEndpoint() {
        cachedEndpoint = null
    }

    /** 用统一注入的工厂构造客户端，避免调用点各自拼装 origin/token。 */
    fun clientFor(endpoint: KimiWebEndpoint.Endpoint): KimiWebApiClient =
        clientFactory(endpoint.origin, endpoint.token)

    internal fun cachedEndpointOrNull(): KimiWebEndpoint.Endpoint? = cachedEndpoint

    /**
     * 取得（或创建）绑定到某个对话的会话 id。
     *
     * 绑定的 id 可能因服务端重启而失效，调用方在收到 SESSION_NOT_FOUND
     * 时应清理绑定并重试，因此这里额外暴露 [forgetSession]。
     *
     * 会话标题优先用对话标题（人类可读，方便在 Kimi 面板里定位），
     * 缺省时退化为工作目录的末级目录名。
     */
    fun sessionFor(
        client: KimiWebApiClient,
        cwd: String,
        bindingKey: String,
        title: String? = null,
    ): String {
        sessionBindings[bindingKey]?.takeIf { it.isNotBlank() }?.let { return it }
        val session = client.createSession(cwd, title = title?.takeIf { it.isNotBlank() }
            ?: cwd.substringAfterLast('/').ifBlank { null })
        bind(bindingKey, session.id)
        return session.id
    }

    fun forgetSession(bindingKey: String) {
        unbind(bindingKey)
    }

    internal fun cachedSessionCount(): Int = sessionBindings.size

    /** 绑定是否已存在（供测试与诊断使用）。 */
    internal fun boundSessionId(bindingKey: String): String? = sessionBindings[bindingKey]

    private fun bind(bindingKey: String, sessionId: String) {
        sessionBindings[bindingKey] = sessionId
        bindingStore.save(sessionBindings.toMap())
    }

    private fun unbind(bindingKey: String) {
        sessionBindings.remove(bindingKey)
        bindingStore.save(sessionBindings.toMap())
    }

    private companion object {
        /** 与 [io.github.mangi.eta.ui.app.KimiWebSession.COMMAND] 保持一致的历史命令也视为可用。 */
        val WEB_COMMANDS = setOf("kimi web --no-open", "kimi web")
    }
}