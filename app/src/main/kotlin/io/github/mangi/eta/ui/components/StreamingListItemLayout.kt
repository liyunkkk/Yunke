package io.github.mangi.eta.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

/**
 * 流式回答里尚未开始显现的列表项：整行（marker、正文以及其后的上下 padding）折叠为 0 高度。
 *
 * 子内容无论 [visible] 与否都必须组合并测量。显现协调器只有收到正文的 onTextLayout
 * 并登记排版后才会推进该块，若隐藏行跳过组合或测量，协调器永远等不到下一项，显现会死锁。
 * 隐藏时只是不放置子内容，也就不会绘制；宽度仍报告真实测量宽度，恢复时无需重新布局宽度。
 *
 * 必须放在上下 padding 之外，否则 padding 仍会把每个未开始的行撑出高度。
 */
internal fun Modifier.streamingListItemLayout(visible: Boolean): Modifier =
    this then StreamingListItemLayoutElement(visible)

// The list observes the coordinator's entire started-key set. Starting another item
// can recompose this row without changing its visibility. A captured layout lambda
// has a new identity on each such call and needlessly invalidates measurement;
// value equality here only updates the node when this row's visibility changes.
private data class StreamingListItemLayoutElement(
    val visible: Boolean,
) : ModifierNodeElement<StreamingListItemLayoutNode>() {
    override fun create(): StreamingListItemLayoutNode = StreamingListItemLayoutNode(visible)

    override fun update(node: StreamingListItemLayoutNode) {
        node.visible = visible
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "streamingListItemLayout"
        properties["visible"] = visible
    }
}

private class StreamingListItemLayoutNode(
    var visible: Boolean,
) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val diagnoseHidden = !visible && StreamPerformanceDiagnostics.enabled
        val measureItem = {
            // 始终测量一次：保留节点注册与 onTextLayout 回调。
            val placeable = measurable.measure(constraints)
            if (visible) {
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            } else {
                if (diagnoseHidden) {
                    StreamPerformanceDiagnostics.record("markdown.hidden.childHeight", value = placeable.height.toLong())
                    StreamPerformanceDiagnostics.record("markdown.hidden.reportHeight", value = constraints.minHeight.toLong())
                }
                // 父级若强制最小高度则只能折叠到该下限；列表 Column 中 minHeight 为 0。
                layout(placeable.width, constraints.minHeight) {}
            }
        }
        return if (diagnoseHidden) {
            StreamPerformanceDiagnostics.measureDetail("markdown.hidden.measure", block = measureItem)
        } else {
            measureItem()
        }
    }
}
