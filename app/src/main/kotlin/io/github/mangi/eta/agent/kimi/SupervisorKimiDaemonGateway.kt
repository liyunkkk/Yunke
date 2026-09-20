package io.github.mangi.eta.agent.kimi

import android.content.Context
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository

/**
 * 将 [DetachedTaskSupervisor] 适配为 [KimiDaemonGateway]。
 *
 * 与 UI 侧的 `KimiWebLauncher` 一样复用守护任务宿主而不是新起前台终端：
 * 守护任务通过 setsid 脱离命令会话进程组，不受终端回收影响，日志落在
 * 工作区文件里可反复回读，因此 `kimi web` 的启动横幅（端口 + Token）
 * 可以在任意时刻被重新解析。
 *
 * 身份与执行环境在 [start] 时按当前 Linux 设置重新解析，避免把
 * PRoot（user 身份）与 chroot（root 身份）混用。
 */
internal class SupervisorKimiDaemonGateway(
    private val context: Context,
    private val supervisor: DetachedTaskSupervisor,
) : KimiDaemonGateway {

    override fun list(): List<KimiDaemonTask> = supervisor.list().map { status ->
        KimiDaemonTask(
            taskId = status.task.id,
            running = status.running,
            command = status.task.command,
        )
    }

    override fun start(): String? {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val environment = distribution.terminalEnvironment
        val rootfsPath = LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
        val identity = TerminalRuntime.defaultIdentity(environment, rootfsPath)
        return when (
            val result = supervisor.start(
                command = WEB_COMMAND,
                cwd = WORKSPACE,
                identity = identity,
                environment = environment,
            )
        ) {
            is DaemonStartResult.Started -> result.task.id
            is DaemonStartResult.Failed -> null
        }
    }

    override fun logs(taskId: String): String? =
        supervisor.readLogs(taskId).let { if (it.ok) it.text else null }

    companion object {
        /** 与 `KimiWebSession.COMMAND` 一致：不在容器内自动打开浏览器。 */
        const val WEB_COMMAND = "kimi web --no-open"

        /** Kimi Code 在容器内的固定工作目录。 */
        const val WORKSPACE = "/workspace"
    }
}
