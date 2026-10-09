package io.github.mangi.eta.ui.app

import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastRoundToInt
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * 默认导航转场的逐字副本，额外报告“导航动画是否还在进行”。
 *
 * 判定只用同一个延迟读取块里已有的事件即可：
 * - [NavTransitionScope.gesture] 非空：手指正驱动横滑返回或预测性返回；
 * - [NavTransitionScope.settle] 存在：释放后的回弹，或普通入栈／出栈过渡。
 * 两者都在动画结束时回到 null，因此除开始与结束外不改变这个布尔值，
 * 不额外触发重组，也不新增图层、位移、缩放或透明度。视觉与默认预设完全相同。
 *
 * 动画期间上一页会露出一部分，正文必须继续实时输出；
 * 只有停稳且完全盖住后才允许停止推进，这样横滑漏一点点、漏一半都不会停在旧画面。
 */
internal fun navigationAwareMiuixTransition(
    onInProgressChanged: (Boolean) -> Unit,
): NavTransition {
    var lastReported: Boolean? = null
    return navGraphicsTransition(opaqueDepth = 1f) { scope ->
        val inProgress = scope.gesture != null || scope.settle != null
        if (inProgress != lastReported) {
            lastReported = inProgress
            onInProgressChanged(inProgress)
        }
        applyMiuixDefaultTransform(scope)
    }
}

/** 默认预设的视觉本体，与库内实现逐字一致，仅改为可共用。 */
private fun GraphicsLayerScope.applyMiuixDefaultTransform(scope: NavTransitionScope) {
    val width = scope.layoutSize.width.toFloat()
    val d = scope.relativeDepth
    val rtl = scope.layoutDirection == LayoutDirection.Rtl
    if (d <= 0f) {
        // 进入／离开顶层：整宽从尾边滑入（RTL 镜像），对齐整设备像素避免边缘闪烁。
        translationX = ((if (rtl) -1f else 1f) * (-d).coerceIn(0f, 1f) * width).fastRoundToInt().toFloat()
    } else {
        // 被覆盖：向首边视差四分之一宽度，并带轻微透明度衰减。
        translationX = (if (rtl) 1f else -1f) * coverProgress(d) * width * 0.25f
        alpha = 1f - 0.1f * coverProgress(d)
    }
}

private fun coverProgress(d: Float): Float = d.coerceIn(0f, 1f)
