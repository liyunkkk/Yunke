package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONObject
import kotlin.math.abs

/**
 * 副屏无进展熔断守卫。
 *
 * 记录停滞窗口内的副屏动作序列：
 * 1. 节点树回读可用时（summary 包含 screen_changed）：
 *    - 若 screen_changed == true -> 界面发生可见变化，立即 reset() 清空停滞窗口；
 *    - 若 screen_changed == false -> 界面无变化，同一目标（坐标相差 <= 48px 或 index 相同）
 *      重复 >= 3 次熔断，或停滞窗口内累计动作 >= 6 次熔断。
 * 2. 节点树回读不可用或截图坐标模式时（summary == null 或不含 screen_changed）：
 *    - 不计入主判定，改用独立且宽松的盲区判定：同一目标重复 >= 8 次或累计动作 >= 12 次才熔断，
 *      防止误杀正在正常执行坐标模式的任务。
 *
 * 熔断后该会话后续副屏动作返回明确错误码 VIRTUAL_NO_PROGRESS_PAUSED，阻止进一步重复操作。
 * 界面变化、包名变化、作用域变化或新 run 时重置守卫。
 * 保证线程安全。
 */
internal class VirtualDisplayProgressGuard {

    sealed class ActionTarget {
        abstract fun matches(other: ActionTarget): Boolean

        data class Point(val x: Int, val y: Int) : ActionTarget() {
            override fun matches(other: ActionTarget): Boolean =
                other is Point &&
                    abs(x - other.x) <= COORDINATE_TOLERANCE_PX &&
                    abs(y - other.y) <= COORDINATE_TOLERANCE_PX
        }

        data class Area(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : ActionTarget() {
            override fun matches(other: ActionTarget): Boolean =
                other is Area &&
                    abs(x1 - other.x1) <= COORDINATE_TOLERANCE_PX &&
                    abs(y1 - other.y1) <= COORDINATE_TOLERANCE_PX &&
                    abs(x2 - other.x2) <= COORDINATE_TOLERANCE_PX &&
                    abs(y2 - other.y2) <= COORDINATE_TOLERANCE_PX
        }

        data class Element(val index: Int) : ActionTarget() {
            override fun matches(other: ActionTarget): Boolean =
                other is Element && index == other.index
        }

        data class Key(val button: String) : ActionTarget() {
            override fun matches(other: ActionTarget): Boolean =
                other is Key && button == other.button
        }

        data class Scroll(val direction: String, val index: Int?) : ActionTarget() {
            override fun matches(other: ActionTarget): Boolean =
                other is Scroll && direction == other.direction && index == other.index
        }

        data class Text(val mode: String, val index: Int?, val text: String) : ActionTarget() {
            override fun matches(other: ActionTarget): Boolean =
                other is Text && mode == other.mode && index == other.index && text == other.text
        }

        data class Generic(val descriptor: String) : ActionTarget() {
            override fun matches(other: ActionTarget): Boolean =
                other is Generic && descriptor == other.descriptor
        }
    }

    private data class ActionRecord(
        val tool: String,
        val target: ActionTarget,
    )

    private var paused = false
    private var pauseReason: String? = null
    private var lastTreeSignature: String? = null
    private var lastPackageName: String? = null
    private val stalledActions = mutableListOf<ActionRecord>()
    private val blindActions = mutableListOf<ActionRecord>()

    @Synchronized
    fun isPaused(): Boolean = paused

    @Synchronized
    fun pauseReason(): String? = pauseReason

    /** 显式重置守卫（例如切应用、新作用域等）。 */
    @Synchronized
    fun reset() {
        paused = false
        pauseReason = null
        stalledActions.clear()
        blindActions.clear()
    }

    /** 作用域变化（包名变了、换应用等）时重置。 */
    @Synchronized
    fun onScopeChanged(packageName: String = "") {
        if (packageName.isNotBlank() && packageName != lastPackageName) {
            lastPackageName = packageName
            lastTreeSignature = null
            reset()
        }
    }

    /**
     * 在 observe_screen 拿到节点快照时调用。
     * 若包名变化或 UI 节点树签名发生变化，说明界面有了新进展，重置守卫。
     */
    @Synchronized
    fun onObservation(packageName: String, nodes: List<UiNode>) {
        if (packageName.isNotBlank()) {
            if (lastPackageName != null && packageName != lastPackageName) {
                lastPackageName = packageName
                lastTreeSignature = null
                reset()
                return
            }
            lastPackageName = packageName
        }

        val currentSignature = computeSignature(nodes)
        if (lastTreeSignature != null && currentSignature != lastTreeSignature) {
            reset()
        }
        lastTreeSignature = currentSignature
    }

    /**
     * 动作执行完成且拿到回读（afterActionSummary）后调用。
     * 区分三种情况：
     * 1. summary == null 或不含 "screen_changed" 键（回读不可用/坐标模式）：走宽松的盲区计数；
     * 2. summary != null 且 screen_changed == true：界面有变化，清空停滞窗口；
     * 3. summary != null 且 screen_changed == false：按主规则计入同一目标 >= 3 或累计 >= 6 熔断。
     */
    @Synchronized
    fun recordAction(tool: String, target: ActionTarget, summary: JSONObject?) {
        val packageChanged = summary?.optBoolean("package_changed", false) ?: false
        val currentPackage = summary?.optString("package").orEmpty()

        if (packageChanged || (currentPackage.isNotBlank() && lastPackageName != null && currentPackage != lastPackageName)) {
            if (currentPackage.isNotBlank()) lastPackageName = currentPackage
            reset()
            return
        }

        if (currentPackage.isNotBlank()) {
            lastPackageName = currentPackage
        }

        // 1) summary == null 或 summary 里不含 "screen_changed" 键（回读不可用 / 坐标模式）
        if (summary == null || !summary.has("screen_changed")) {
            blindActions.add(ActionRecord(tool, target))
            val sameCount = blindActions.count { it.tool == tool && it.target.matches(target) }
            if (sameCount >= MAX_BLIND_SAME_TARGET_ATTEMPTS) {
                paused = true
                pauseReason = "坐标模式下同一目标重复操作达到 $sameCount 次且无进展"
                return
            }
            if (blindActions.size >= MAX_BLIND_STALLED_ACTIONS) {
                paused = true
                pauseReason = "坐标模式下累计动作达到 ${blindActions.size} 次且无进展"
                return
            }
            return
        }

        // 2) summary 存在且 screen_changed == true -> 界面有变化，reset() 清空停滞窗口
        val screenChanged = summary.optBoolean("screen_changed", false)
        if (screenChanged) {
            reset()
            return
        }

        // 3) summary 存在且 screen_changed == false -> 按现有规则计入 check1（同一目标 >=3）与 check2（累计 >=6）
        blindActions.clear()
        stalledActions.add(ActionRecord(tool, target))

        val sameCount = stalledActions.count { it.tool == tool && it.target.matches(target) }
        if (sameCount >= MAX_SAME_TARGET_ATTEMPTS) {
            paused = true
            pauseReason = "同一目标重复操作达到 $sameCount 次且无进展"
            return
        }

        if (stalledActions.size >= MAX_STALLED_ACTIONS) {
            paused = true
            pauseReason = "当前停滞窗口内累计动作达到 ${stalledActions.size} 次且无进展"
            return
        }
    }

    private fun computeSignature(nodes: List<UiNode>): String =
        nodes.joinToString(";") {
            "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}|${it.checked}|${it.selected}|${it.enabled}"
        }

    companion object {
        const val MAX_SAME_TARGET_ATTEMPTS = 3
        const val MAX_STALLED_ACTIONS = 6

        const val MAX_BLIND_SAME_TARGET_ATTEMPTS = 8
        const val MAX_BLIND_STALLED_ACTIONS = 12

        const val COORDINATE_TOLERANCE_PX = 48
        const val ERROR_CODE = "VIRTUAL_NO_PROGRESS_PAUSED"
        const val ERROR_MESSAGE = "已检测到无进展，请停止重复操作并向用户报告卡在哪一步"
    }
}
