package io.github.mangi.eta.agent.kimi

import android.content.Context
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository

/**
 * Kimi 子代理的只读状态快照。
 *
 * 由 `sessionStatus` + `sessionSummary` 组装；面板据此展示执行中/空闲、模型、
 * 上下文占用与上一轮是否失败。任一步接口异常都返回 null，让面板优雅降级为「未启动」。
 */
internal data class KimiSubAgentStatus(
    val busy: Boolean,
    val model: String?,
    val contextTokens: Int?,
    val maxContextTokens: Int?,
    val lastTurnFailed: Boolean,
)

/** 只读状态读取：失败返回 null，不抛异常。 */
internal object KimiSubAgentStatusReader {

    fun read(client: KimiWebApiClient, sessionId: String): KimiSubAgentStatus? = runCatching {
        val status = client.sessionStatus(sessionId)
        // 摘要在服务端异常时可能读不到；只要会话状态可用就仍然返回状态，失败标记缺省为 false。
        val summary = runCatching { client.sessionSummary(sessionId) }.getOrNull()
        KimiSubAgentStatus(
            busy = status.busy,
            model = status.model ?: summary?.model,
            contextTokens = status.contextTokens.takeIf { it > 0 },
            maxContextTokens = status.maxContextTokens.takeIf { it > 0 },
            lastTurnFailed = summary?.failed == true,
        )
    }.getOrNull()
}

/**
 * UI 只读接入。
 *
 * 只在**已有运行中的** `kimi web` 服务端时读取状态、默认模型与最近消息，
 * 绝不因为打开面板而启动新的守护进程；无端点或无会话时返回 null/空表。
 */
internal object KimiSubAgentStatusAccess {

    fun readStatus(context: Context, bindingKey: String): KimiSubAgentStatus? {
        val service = runCatching { service(context) }.getOrNull() ?: return null
        val endpoint = runCatching { service.runningEndpoint() }.getOrNull() ?: return null
        val sessionId = runCatching { service.boundSessionId(bindingKey) }.getOrNull() ?: return null
        return KimiSubAgentStatusReader.read(service.clientFor(endpoint), sessionId)
    }

    /** 服务端当前生效的 `default_model`；读不到返回 null。 */
    fun defaultModel(context: Context): String? {
        val service = runCatching { service(context) }.getOrNull() ?: return null
        val endpoint = runCatching { service.runningEndpoint() }.getOrNull() ?: return null
        return runCatching { service.clientFor(endpoint).defaultModel() }.getOrNull()
    }

    /** 最近若干条消息，用于状态弹窗的「查看消息」入口；失败返回空表。 */
    fun recentMessages(context: Context, bindingKey: String, limit: Int = 12): List<KimiMessage> {
        val service = runCatching { service(context) }.getOrNull() ?: return emptyList()
        val endpoint = runCatching { service.runningEndpoint() }.getOrNull() ?: return emptyList()
        val sessionId = runCatching { service.boundSessionId(bindingKey) }.getOrNull() ?: return emptyList()
        return runCatching { service.clientFor(endpoint).listMessages(sessionId, limit = limit) }.getOrDefault(emptyList())
    }

    private fun service(context: Context): KimiWebService {
        val appContext = context.applicationContext
        return KimiWebService(
            daemon = SupervisorKimiDaemonGateway(appContext, gateway(appContext)),
            bindingStore = FileKimiSessionBindingStore(appContext),
        )
    }

    private fun gateway(context: Context): DetachedTaskSupervisor = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
}
