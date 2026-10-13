package io.github.mangi.eta.agent.display

// NOTE(Yunke 移植): 归一化逻辑移至同包 VirtualScreenIdleTimeout（原 io.github.mangi.eta.data.model）

/** Viewer frame polling does not prolong an idle session; running tasks never expire. */
internal class VirtualScreenIdleTimer(
    minutes: Int,
    private val now: () -> Long,
    active: Boolean = false,
) {
    private var timeoutMinutes = VirtualScreenIdleTimeout.normalize(minutes)
    private var running = active
    private var lastActivity = now()

    @Synchronized
    fun update(minutes: Int, active: Boolean) {
        timeoutMinutes = VirtualScreenIdleTimeout.normalize(minutes)
        if (running != active) lastActivity = now()
        running = active
    }

    @Synchronized
    fun touch() { lastActivity = now() }

    @Synchronized
    fun expired(): Boolean = !running && timeoutMinutes > 0 &&
        now() - lastActivity >= timeoutMinutes * 60_000L
}
