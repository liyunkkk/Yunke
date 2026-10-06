package io.github.mangi.eta

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Startup-only gate, never invoked by configuration reads or external imports. Its caller must run
 * this before work/draft initialization. Recovery and reset share one execution-maintenance fence.
 * A failed attempt keeps that fence; calling initializeBeforeWork again is an explicit recovery retry.
 */
internal class SubAgentConfigurationResetLifecycle(
    private val beginMaintenance: () -> Unit,
    private val recoverInterruptedRestoreBeforeWork: suspend () -> Unit,
    private val recoverConfigurationDurability: () -> Boolean,
    private val resetLegacyConfigurationOnce: () -> Boolean,
    private val isConfigurationResetComplete: () -> Boolean,
    private val endMaintenance: () -> Unit,
) {
    private val mutex = Mutex()
    private var maintenanceHeld = false
    private var initialized = false

    suspend fun initializeBeforeWork(isMainProcess: Boolean): Boolean = mutex.withLock {
        if (!isMainProcess) return@withLock false
        if (initialized) return@withLock true
        if (!maintenanceHeld) {
            beginMaintenance()
            maintenanceHeld = true
        }
        // No finally-unlock: failed journal recovery or an unknown preferences commit blocks work.
        recoverInterruptedRestoreBeforeWork()
        check(recoverConfigurationDurability()) { "子代理配置持久化恢复未完成，不能准入任务" }
        check(resetLegacyConfigurationOnce()) { "子代理配置重置失败，不能准入任务" }
        check(isConfigurationResetComplete()) { "子代理配置重置未确认落盘，不能准入任务" }
        endMaintenance()
        maintenanceHeld = false
        initialized = true
        true
    }
}
