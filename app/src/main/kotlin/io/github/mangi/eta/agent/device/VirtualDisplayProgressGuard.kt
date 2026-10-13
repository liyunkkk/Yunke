package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONObject
import kotlin.math.abs

/**
 * 副屏无进展熔断守卫。
 *
 * 记录停滞窗口内的副屏动作序列：
 * 1. 节点类动作（tap_element、long_press_element、scroll_element）且回读包含 screen_changed：
 *    - 若 screen_changed == true -> 界面发生可见变化，立即 reset() 清空停滞窗口；
 *    - 若 screen_changed == false -> 界面无变化，同一目标（index 相同等）
 *      重复 >= 3 次熔断，或自上次观察以来累计动作 >= 6 次熔断。
 * 2. 坐标类动作（tap、tap_area、swipe、long_press、scroll、press_key）或回读不可用时：
 *    - 一律改用宽松阈值：同一目标重复 >= 8 次或自上次观察以来累计动作 >= 12 次才熔断，
 *      防止误杀内容恒定界面（画布、视频、游戏、图标网格等）下的正常坐标操作。
 * 3. 严格与宽松模式各自独立计数，互不清空；同一目标重复计数在整个停滞窗口内保留。
 * 4. 「累计动作」只统计自上次观察以来的连续动作数——一次 observe_screen 即归零。
 *    因此该规则的实际口径是「连续 N 次动作之间没有任何观察」；同一目标重复计数不受观察影响。
 *    这避免坐标模式下节点树恒定不变时把正常任务判死。
 * 5. 熔断后的逃逸口：launch_app 成功会无条件重置守卫（视为一次有意的重新开始）。
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

    /** 自上次观察以来的连续无进展动作数；一次 observe_screen 即归零。 */
    private var stalledSinceObservation = 0
    private var blindSinceObservation = 0

    @Synchronized
    fun isPaused(): Boolean = paused

    @Synchronized
    fun pauseReason(): String? = pauseReason

    @Synchronized
    fun pausedErrorMessage(): String =
        pauseReason?.let { "$ERROR_MESSAGE：$it" } ?: ERROR_MESSAGE

    /** 显式重置守卫（例如切应用、新作用域等）。 */
    @Synchronized
    fun reset() {
        paused = false
        pauseReason = null
        stalledActions.clear()
        blindActions.clear()
        stalledSinceObservation = 0
        blindSinceObservation = 0
    }

    /**
     * 启动/接管应用时重置。
     *
     * 这里**无条件**重置（不再要求包名变化）：启动应用是一次有意的重新开始，
     * 同时这也给熔断提供了应用内逃逸口——熔断后仍放行 launch_app，
     * 因此模型/用户可以靠重新打开应用解除误判，而不必换应用或重开会话。
     */
    @Synchronized
    fun onScopeChanged(packageName: String = "") {
        if (packageName.isBlank()) return
        lastPackageName = packageName
        lastTreeSignature = null
        reset()
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
        // 一次观察视为重新接地：累计动作计数归零；
        // 但不清空列表本身——同一目标重复计数必须保留，否则 observe→tap→observe→tap 的循环会漏判。
        stalledSinceObservation = 0
        blindSinceObservation = 0
    }

    /**
     * 动作执行完成且拿到回读（afterActionSummary）后调用。
     * 区分判定阈值：
     * 1. 坐标类动作（tap、tap_area、swipe、long_press、scroll、press_key）或回读不可用：
     *    一律走宽松阈值（同一目标 >= 8 或自上次观察以来累计 >= 12），避免误杀内容恒定界面下的正常操作；
     * 2. 节点类动作（tap_element、long_press_element、scroll_element）：
     *    - 回读含 screen_changed 且为 true -> 界面有变化，清空停滞窗口；
     *    - 回读含 screen_changed 且为 false -> 严格阈值（同一目标 >= 3 或自上次观察以来累计 >= 6）；
     *    - 回读不含 screen_changed -> 宽松阈值（同一目标 >= 8 或自上次观察以来累计 >= 12）。
     * 3. 严格与宽松模式各自独立计数，互不清空；同一目标重复计数在整个停滞窗口内保留。
     * 4. 「累计动作」只统计自上次观察以来的连续动作数（一次 observe_screen 即归零），
     *    实际口径是「连续 N 次动作之间没有任何观察」；同一目标重复计数不受观察影响。
     */
    @Synchronized
    fun recordAction(
        tool: String,
        target: ActionTarget,
        summary: JSONObject?,
        nodeBased: Boolean = isNodeBased(tool),
    ) {
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

        val screenChanged = summary?.optBoolean("screen_changed", false) ?: false
        if (screenChanged) {
            reset()
            return
        }

        val hasScreenChanged = summary != null && summary.has("screen_changed")
        if (nodeBased && hasScreenChanged) {
            // 节点类动作且有明确回读（screen_changed == false）：走严格判定（>= 3 或 >= 6）
            stalledActions.add(ActionRecord(tool, target))
            stalledSinceObservation++

            val sameCount = stalledActions.count { it.tool == tool && it.target.matches(target) }
            if (sameCount >= MAX_SAME_TARGET_ATTEMPTS) {
                paused = true
                pauseReason = "同一目标重复操作达到 $sameCount 次且无进展"
                return
            }

            if (stalledSinceObservation >= MAX_STALLED_ACTIONS) {
                paused = true
                pauseReason = "自上次观察以来累计动作达到 $stalledSinceObservation 次且无进展"
                return
            }
        } else {
            // 坐标类动作或回读不可用：一律走宽松判定（>= 8 或 >= 12）
            blindActions.add(ActionRecord(tool, target))
            blindSinceObservation++

            val sameCount = blindActions.count { it.tool == tool && it.target.matches(target) }
            if (sameCount >= MAX_BLIND_SAME_TARGET_ATTEMPTS) {
                paused = true
                pauseReason = "坐标模式下同一目标重复操作达到 $sameCount 次且无进展"
                return
            }

            if (blindSinceObservation >= MAX_BLIND_STALLED_ACTIONS) {
                paused = true
                pauseReason = "坐标模式下自上次观察以来累计动作达到 $blindSinceObservation 次且无进展"
                return
            }
        }
    }

    private fun computeSignature(nodes: List<UiNode>): String =
        nodes.joinToString(";") {
            "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}|${it.checked}|${it.selected}|${it.enabled}"
        }

    companion object {
        val NODE_BASED_TOOLS = setOf("tap_element", "long_press_element", "scroll_element")

        fun isNodeBased(tool: String): Boolean = tool in NODE_BASED_TOOLS

        const val MAX_SAME_TARGET_ATTEMPTS = 3
        const val MAX_STALLED_ACTIONS = 6

        const val MAX_BLIND_SAME_TARGET_ATTEMPTS = 8
        const val MAX_BLIND_STALLED_ACTIONS = 12

        const val COORDINATE_TOLERANCE_PX = 48
        const val ERROR_CODE = "VIRTUAL_NO_PROGRESS_PAUSED"
        const val ERROR_MESSAGE = "已检测到无进展，请停止重复操作并向用户报告卡在哪一步"
    }
}
