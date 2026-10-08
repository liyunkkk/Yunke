package io.github.mangi.eta.agent.device

import android.os.SystemClock

/**
 * 副屏节点可用性的时序判定：空树是"某个窗口此刻的证据"，不是"这块屏幕永久的能力"。
 *
 * 同一作用域（run + display + 包名）内连续空树达到 3 次、且首次到最近一次跨度不少于 1.5 秒，
 * 才把节点能力暂时标记为不可用；一旦取到节点或作用域变化立即恢复。
 * 移植自 Mangi-11/Eta v3.3.0 的 VirtualScreenUiTreeAvailability。
 */
internal class VirtualDisplayUiTreeAvailability(
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private data class Scope(val runId: String, val displayId: Int, val packageName: String)

    private var scope: Scope? = null
    private var firstEmptyAt: Long? = null
    private var lastEmptyAt: Long? = null
    private var emptyAttempts = 0
    private var unavailable = false

    /** 作用域变化（换应用、换副屏、换 run）时清空全部证据。 */
    fun updateScope(runId: String, displayId: Int, packageName: String) {
        val next = Scope(runId, displayId, packageName)
        if (scope == next) return
        scope = next
        clearEvidence()
    }

    fun unavailable(): Boolean = unavailable

    /** 记录一次观察结果，返回"当前是否判定为不可用"。 */
    fun record(hasNodes: Boolean): Boolean {
        if (hasNodes) {
            clearEvidence()
            return false
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

    private fun clearEvidence() {
        firstEmptyAt = null
        lastEmptyAt = null
        emptyAttempts = 0
        unavailable = false
    }

    private companion object {
        const val MIN_EMPTY_ATTEMPTS = 3
        const val MIN_ATTEMPT_INTERVAL_MS = 500L
        const val EMPTY_GRACE_MS = 1500L
    }
}
