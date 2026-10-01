package io.github.mangi.eta.agent.device

/**
 * 一次 run 内“每次询问”只问一次：选定后复用，取消或超时后记住，之后直接返回 null 不再弹窗。
 * 并行调用在锁里排队，第一个有结果后其余直接复用。RUN_CLOSED 由调用方在进门前自行判断。
 *
 * 可供 AgentLocalTools 使用；也便于纯 JVM 测试。
 */
internal class AgentTaskSurfaceChoiceGate {
    private val lock = Any()

    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var chosen: AgentTaskSurfaceMode? = null
        private set

    fun resolve(choose: () -> AgentTaskSurfaceMode?): AgentTaskSurfaceMode? {
        chosen?.let { return it }
        if (cancelled) return null
        synchronized(lock) {
            chosen?.let { return it }
            if (cancelled) return null
            val mode = choose()?.takeIf { it != AgentTaskSurfaceMode.ASK }
            if (mode == null) cancelled = true else chosen = mode
            return mode
        }
    }
}
