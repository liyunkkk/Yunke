package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作后观察的模型投影：先给结论（界面是否变化、是否切换应用或窗口），再给新的节点列表。
 * 变化判定只比较节点的可见语义（类名、view_id、文本、描述、位置），不依赖时间戳或动画帧。
 * 移植自 Mangi-11/Eta v3.3.0（AgentAfterActionSummary），字段名对齐本仓库设备侧模型。
 */
internal object AgentAfterActionSummary {
    /**
     * @param diagnosticOnly 主屏路径传 true：节点列表只用于判断动作是否生效，observation_id 并未发布，
     *   note 因此不能声称「可直接用该 id 继续操作」，否则模型会拿旧 id 去 tap_element 并拿到
     *   STALE_OBSERVATION。副屏路径传 false：其 observation_id 已发布，可直接续用。
     */
    fun build(
        before: ElementObservation?,
        after: ElementObservation,
        diagnosticOnly: Boolean = false,
    ): JSONObject {
        val changed = before == null || signature(before.nodes) != signature(after.nodes)
        return JSONObject()
            .put("observation_id", after.id)
            .put("package", after.packageName)
            .put("screen_changed", changed)
            .put("package_changed", before != null && before.packageName != after.packageName)
            .put("window_changed", before != null && before.windowId != after.windowId)
            .put("ui_tree_truncated", after.truncated)
            .put("ui_nodes", JSONArray().also { array -> after.nodes.forEach { array.put(it.compactJson()) } })
            .put(
                "note",
                when {
                    changed && diagnosticOnly ->
                        "以上为动作后的界面快照，仅用于判断动作是否生效；该 observation_id 未发布，" +
                            "不能拿去 tap_element，需要节点索引时先调用 observe_screen"
                    changed ->
                        "以上为动作后的新界面，可直接用该 observation_id 继续操作；需要更多节点或截图时再调用 observe_screen"
                    else -> "界面没有可见变化，动作可能未生效；请换目标或方式，不要原样重复"
                },
            )
    }

    /**
     * 变化判定用的可见语义签名。
     *
     * 除类名、view_id、文本、描述与位置外，还要带上勾选/选中/可用状态：开关、单选、复选框
     * 变化时节点其余字段完全不变，漏掉这三位会把「已生效」误判成「界面没有变化」。
     */
    private fun signature(nodes: List<UiNode>): List<String> =
        nodes.map {
            "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}" +
                "|${it.checked}|${it.selected}|${it.enabled}"
        }

    /** 精简字段：省略默认值为 false 的布尔与包名，降低每步附带观察的 token 开销。 */
    private fun UiNode.compactJson(): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (text.isNotBlank()) put("text", text)
            if (desc.isNotBlank()) put("desc", desc)
            put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            put("center", JSONObject().put("x", centerX).put("y", centerY))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (editable) put("editable", true)
            if (focused) put("focused", true)
            if (password) put("password", true)
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            if (hint.isNotBlank()) put("hint", hint)
        }
}
