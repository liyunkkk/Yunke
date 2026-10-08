package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 节点投影与 JSON 序列化的单一来源：主屏路径与副屏路径共用同一套字段，
 * 避免两处各自维护导致字段漂移。
 */
internal object DeviceNodeProjection {
    fun projectOne(node: AgentAccessibilityService.UiNode): RootShellDeviceController.UiNode =
        RootShellDeviceController.UiNode(
            index = node.index,
            text = node.text,
            desc = node.desc,
            className = node.className,
            packageName = node.packageName,
            viewId = node.viewId,
            bounds = node.bounds,
            clickable = node.clickable,
            longClickable = node.longClickable,
            scrollable = node.scrollable,
            focused = node.focused,
            editable = node.editable,
            password = node.password,
            enabled = node.enabled,
            checked = node.checked,
            selected = node.selected,
            hint = node.hint,
        )

    fun project(nodes: List<AgentAccessibilityService.UiNode>): List<RootShellDeviceController.UiNode> =
        nodes.map { projectOne(it) }

    fun json(nodes: List<RootShellDeviceController.UiNode>): JSONArray =
        JSONArray().also { array -> nodes.forEach { array.put(nodeJson(it)) } }

    fun nodeJson(node: RootShellDeviceController.UiNode): JSONObject =
        JSONObject()
            .put("index", node.index)
            .put("bounds", node.bounds.toShortString())
            .put("center", JSONObject().put("x", node.centerX).put("y", node.centerY))
            .also { json ->
                node.text.takeIf { it.isNotEmpty() }?.let { json.put("text", it) }
                node.desc.takeIf { it.isNotEmpty() }?.let { json.put("desc", it) }
                node.className.takeIf { it.isNotEmpty() }?.let { json.put("class", it.substringAfterLast('.')) }
                node.viewId.takeIf { it.isNotEmpty() }?.let { json.put("view_id", it) }
                if (node.clickable) json.put("clickable", true)
                if (node.longClickable) json.put("long_clickable", true)
                if (node.scrollable) json.put("scrollable", true)
                if (node.focused) json.put("focused", true)
                if (node.editable) json.put("editable", true)
                if (node.password) json.put("password", true)
                if (!node.enabled) json.put("enabled", false)
                node.checked?.let { json.put("checked", it) }
                if (node.selected) json.put("selected", true)
                node.hint.takeIf { it.isNotEmpty() }?.let { json.put("hint", it) }
            }
}
