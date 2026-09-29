package io.github.mangi.eta.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

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
    this.layout { measurable, constraints ->
        // 始终测量：保留节点注册与 onTextLayout 回调。
        val placeable = measurable.measure(constraints)
        if (visible) {
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        } else {
            // 父级若强制最小高度则只能折叠到该下限；列表 Column 中 minHeight 为 0。
            layout(placeable.width, constraints.minHeight) {}
        }
    }
