package io.github.mangi.eta.agent.accessibility

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 在 GUI 工具执行前确认 Eta 无障碍服务已经真实连接。
 *
 * 持久保护、Secure Settings 写入与断连重绑均由 system_server 后端负责。这里不申请
 * Root，也不直接改系统设置；保护关闭或后端不可用时 fail closed。
 */
object AgentAccessibilityKeeper {
    internal fun ensureEnabledForGuiOperation(context: Context): AccessibilityEnableResult {
        val startedAt = SystemClock.elapsedRealtime()
        val result = ensureAvailable(
            serviceAvailable = AgentAccessibilityService::isAvailable,
            protectionEnabled = { AccessibilityProtectionClient.isEnabled(context) },
            requestRecovery = {
                AccessibilityProtectionClient.requestRecoveryBlocking(context) ==
                    AccessibilityProtectionClient.ControlStatus.APPLIED
            },
            awaitServiceBinding = ::awaitServiceBinding,
            protectionAvailable = { EtaApp.serviceInstance != null },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (result.available) {
            AndroidAgentLogger.info(
                "Agent accessibility action=ensure_for_gui outcome=completed " +
                    "recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        } else {
            AndroidAgentLogger.warn(
                "Agent accessibility action=ensure_for_gui outcome=failed " +
                    "code=${result.code} recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        }
        return result
    }

    /**
     * 服务自称可用（实例存在）但取不到窗口/节点时的强自愈：跳过「已连接」短路，
     * 直接请后端按真实可用性重绑，并等待默认屏窗口重新可枚举。
     *
     * 只在调用方已经确认「连默认屏窗口都枚举不出来」时使用，避免应用窗口未就绪时误重绑。
     */
    internal fun forceRecoveryForGuiOperation(context: Context): AccessibilityEnableResult {
        val protectionAvailable = runCatching { EtaApp.serviceInstance != null }.getOrDefault(false)
        val protectionEnabled = runCatching { AccessibilityProtectionClient.isEnabled(context) }.getOrDefault(false)
        if (!protectionAvailable || !protectionEnabled) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
                message = "无障碍保护后端不可用；本次未做重绑",
                recoveryRequested = false,
            )
        }
        if (
            runCatching { AccessibilityProtectionClient.requestRecoveryBlocking(context) }.getOrNull() !=
            AccessibilityProtectionClient.ControlStatus.APPLIED
        ) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
                message = "无障碍恢复请求未被后端接受；本次未做重绑",
                recoveryRequested = true,
            )
        }
        if (!awaitUsableWindowContent()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_REPAIR_TIMEOUT",
                message = "YUNKe 无障碍服务未在恢复时限内恢复取窗口能力",
                recoveryRequested = true,
            )
        }
        return AccessibilityEnableResult.available(recoveryRequested = true)
    }

    private fun awaitUsableWindowContent(): Boolean {
        repeat(SERVICE_BIND_ATTEMPTS) {
            if (AgentAccessibilityService.defaultDisplayWindowsUsable()) return true
            SystemClock.sleep(SERVICE_BIND_POLL_MS)
        }
        return AgentAccessibilityService.defaultDisplayWindowsUsable()
    }

    internal fun ensureAvailable(
        serviceAvailable: () -> Boolean,
        protectionEnabled: () -> Boolean,
        requestRecovery: () -> Boolean,
        awaitServiceBinding: () -> Boolean,
        protectionAvailable: () -> Boolean = { true },
    ): AccessibilityEnableResult {
        if (serviceAvailable()) {
            return AccessibilityEnableResult.available(recoveryRequested = false)
        }
        if (!protectionAvailable() || !protectionEnabled()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_UNAVAILABLE",
                message = "YUNKe 无障碍服务未连接；请在系统设置中开启 YUNKe 无障碍服务",
                recoveryRequested = false,
            )
        }
        if (!requestRecovery()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
                message = "无障碍保护后端不可用；本次 GUI 操作未执行",
                recoveryRequested = true,
            )
        }
        if (!awaitServiceBinding()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_REPAIR_TIMEOUT",
                message = "YUNKe 无障碍服务未在恢复时限内连接；本次 GUI 操作未执行",
                recoveryRequested = true,
            )
        }
        return AccessibilityEnableResult.available(recoveryRequested = true)
    }

    private fun awaitServiceBinding(): Boolean {
        repeat(SERVICE_BIND_ATTEMPTS) {
            if (AgentAccessibilityService.isAvailable()) return true
            SystemClock.sleep(SERVICE_BIND_POLL_MS)
        }
        return AgentAccessibilityService.isAvailable()
    }

    private const val SERVICE_BIND_ATTEMPTS = 60
    private const val SERVICE_BIND_POLL_MS = 100L
}

internal data class AccessibilityEnableResult(
    val available: Boolean,
    val code: String = "",
    val message: String = "",
    val recoveryRequested: Boolean,
) {
    companion object {
        fun available(
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = true,
            recoveryRequested = recoveryRequested,
        )

        fun failure(
            code: String,
            message: String,
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = false,
            code = code,
            message = message,
            recoveryRequested = recoveryRequested,
        )
    }
}
