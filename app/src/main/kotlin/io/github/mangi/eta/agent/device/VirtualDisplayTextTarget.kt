package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode

/**
 * 副屏文本工具的落点选择。
 *
 * 只认本次副屏观察发布的节点：副屏的 replace_text / clear_text 绝不回退主屏输入焦点
 * （无障碍服务的无 index 路径取的是默认屏 rootInActiveWindow，副屏不能用）。
 *
 * 优先获得输入焦点且可编辑的节点；其次该次观察里唯一的可编辑节点；无法唯一确定时返回 null，
 * 由调用方要求模型显式传 index。
 */
internal object VirtualDisplayTextTarget {
    fun pick(nodes: List<UiNode>): UiNode? {
        val editable = nodes.filter { it.editable && it.enabled }
        return editable.firstOrNull { it.focused } ?: editable.singleOrNull()
    }
}
