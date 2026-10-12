package io.github.mangi.eta.agent.device

import android.os.SystemClock

/**
 * 副屏节点可用性的时序判定：空树是"某个窗口此刻的证据"，不是"这块屏幕永久的能力"。
 *
 * 判定规则按顺序：
 * 1. hasNodes 为 true -> 清空全部证据，恢复可用，返回 false。
 * 2. windowId == null && ownerPackage.isBlank() -> 完全无从归因（既无窗口也无 owner 包名），
 *    清空主证据，递增独立的"无法归因"计数；该计数连续达到 8 次且跨度 >= 8 秒触发安全阀，判定 unavailable = true。
 * 3. windowId != null && lastWindowId != null 且两者不同 -> 窗口变化，清空主证据后按规则 4 重新累计。
 * 4. 否则按主规则累计空树：连续 3 次且跨度 >= 1.5 秒判定 unavailable = true。
 *
 * 作用域仍为 (runId, displayId, packageName)；updateScope 变化时清空全部证据（含新计数）。
 * 保证线程安全。
 */
internal class VirtualDisplayUiTreeAvailability(
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private data class Scope(val runId: String, val displayId: Int, val packageName: String)

    private var scope: Scope? = null

    // 主计数证据
    private var lastWindowId: Int? = null
    private var firstEmptyAt: Long? = null
    private var lastEmptyAt: Long? = null
    private var emptyAttempts = 0

    // 无法归因证据（安全阀）
    private var firstUnattributedAt: Long? = null
    private var lastUnattributedAt: Long? = null
    private var unattributedAttempts = 0

    private var unavailable = false

    /** 作用域变化（换应用、换副屏、换 run）时清空全部证据。 */
    @Synchronized
    fun updateScope(runId: String, displayId: Int, packageName: String) {
        val next = Scope(runId, displayId, packageName)
        if (scope == next) return
        scope = next
        clearEvidence()
    }

    @Synchronized
    fun unavailable(): Boolean = unavailable

    /** 记录一次观察结果，返回"当前是否判定为不可用"。 */
    @Synchronized
    fun record(windowId: Int?, ownerPackage: String, hasNodes: Boolean): Boolean {
        if (hasNodes) {
            clearEvidence()
            return false
        }

        // 2. 完全无从归因（既没窗口、也没有 owner 包名）
        if (windowId == null && ownerPackage.isBlank()) {
            clearPrimaryEvidence()
            val now = elapsedRealtime()
            val first = firstUnattributedAt ?: now.also { firstUnattributedAt = it }
            val previous = lastUnattributedAt
            if (previous == null || now - previous >= MIN_ATTEMPT_INTERVAL_MS) {
                lastUnattributedAt = now
                unattributedAttempts++
            }
            unavailable = unavailable || (unattributedAttempts >= MIN_UNATTRIBUTED_ATTEMPTS &&
                now - first >= UNATTRIBUTED_GRACE_MS)
            return unavailable
        }

        // 有归因证据，清空无法归因安全阀计数
        clearUnattributedEvidence()

        // 3. windowId != null && lastWindowId != null 且两者不同：清空主证据，再按 4 计数
        if (windowId != null && lastWindowId != null && windowId != lastWindowId) {
            clearPrimaryEvidence()
        }

        // 4. 按主规则累计空树
        if (windowId != null) {
            lastWindowId = windowId
        }
        val now = elapsedRealtime()
        val first = firstEmptyAt ?: now.also { firstEmptyAt = it }
        val previous = lastEmptyAt
        if (previous == null || now - previous >= MIN_ATTEMPT_INTERVAL_MS) {
            lastEmptyAt = now
            emptyAttempts++
        }
        unavailable = emptyAttempts >= MIN_EMPTY_ATTEMPTS && now - first >= EMPTY_GRACE_MS
        return unavailable
    }

    private fun clearPrimaryEvidence() {
        firstEmptyAt = null
        lastEmptyAt = null
        emptyAttempts = 0
        lastWindowId = null
    }

    private fun clearUnattributedEvidence() {
        firstUnattributedAt = null
        lastUnattributedAt = null
        unattributedAttempts = 0
    }

    private fun clearEvidence() {
        clearPrimaryEvidence()
        clearUnattributedEvidence()
        unavailable = false
    }

    private companion object {
        const val MIN_EMPTY_ATTEMPTS = 3
        const val MIN_ATTEMPT_INTERVAL_MS = 500L
        const val EMPTY_GRACE_MS = 1500L

        const val MIN_UNATTRIBUTED_ATTEMPTS = 8
        const val UNATTRIBUTED_GRACE_MS = 8000L
    }
}
