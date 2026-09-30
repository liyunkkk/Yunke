package io.github.mangi.eta.ui.components

import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 用户拖动和惯性滑动期间，向系统要一个明确的最高帧率，停下后再保留 [SCROLL_FRAME_RATE_TAIL_MS]。
 *
 * 实测（vivo，Android 15）：惯性变慢时系统按内容速度把 Eta 的出帧从 120 逐级降到 90、72、60，
 * 每切一次都掉帧，看起来就是“卡一下再恢复”。锁定显示器 120Hz 也拦不住，因为降的是应用自己的
 * 出帧速率（每两次刷新出一帧）。
 *
 * 两路一起投：根 View 的 requestedFrameRate（只在根 View 重画时才会被系统采纳），以及
 * [PeakFrameRateVote] 每帧重录的 preferredFrameRate 绘制层。
 * 开关时各写一行 FrameRateDiag，用来区分“代码没跑”和“跑了但被系统压掉”。
 *
 * [userScrolling] 只在 snapshotFlow 里读；返回的 State 只由 [PeakFrameRateVote] 读，
 * 滑动开始和结束不会让调用方重组。
 */
@Composable
internal fun rememberScrollFrameRateHold(userScrolling: () -> Boolean): State<Boolean> {
    val hold = remember { mutableStateOf(false) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return hold
    val view = LocalView.current
    val currentUserScrolling by rememberUpdatedState(userScrolling)
    LaunchedEffect(Unit) { driveHold(hold) { currentUserScrolling() } }
    DisposableEffect(view, hold.value) {
        if (!hold.value) return@DisposableEffect onDispose {}
        ScrollFrameRateApi35.request(view)
        onDispose { ScrollFrameRateApi35.clear(view) }
    }
    return hold
}

private suspend fun driveHold(hold: MutableState<Boolean>, userScrolling: () -> Boolean) {
    snapshotFlow { userScrolling() }
        .collectLatest { scrolling ->
            if (scrolling) {
                hold.value = true
            } else {
                delay(SCROLL_FRAME_RATE_TAIL_MS)
                hold.value = false
            }
        }
}

/**
 * [hold] 为真时放一个 1px 的透明绘制层，带 preferredFrameRate，并且每帧重画一次。
 *
 * Compose 只在绘制层更新显示列表时投帧率票，投完的票在当帧 dispatchDraw 里写到根 View 上，
 * 下一帧清空重投。列表本身滑动时多数帧只挪位置、不重录，所以得有一层每帧都重录，
 * 这一票才能每帧都投出去。不持有时整层不存在，不额外画任何东西。
 */
@Composable
internal fun PeakFrameRateVote(hold: State<Boolean>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    if (!hold.value) return
    val view = LocalView.current
    val rate = remember(view) { peakFrameRateOf(view) }
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        AndroidAgentLogger.info("FrameRateDiag vote=on rate=$rate")
        try {
            while (true) withFrameNanos { tick.longValue = it }
        } finally {
            AndroidAgentLogger.info("FrameRateDiag vote=off")
        }
    }
    Box(
        Modifier
            .size(1.dp)
            .preferredFrameRate(rate)
            .drawBehind { tick.longValue },
    )
}

/** 取显示器支持的最高刷新率；拿不到时用 [FALLBACK_PEAK_FRAME_RATE]。 */
internal fun pickPeakFrameRate(supportedRates: List<Float>): Float =
    supportedRates.filter { it.isFinite() && it > 0f }.maxOrNull() ?: FALLBACK_PEAK_FRAME_RATE

private fun peakFrameRateOf(view: View): Float =
    pickPeakFrameRate(view.display?.supportedModes?.map { it.refreshRate }.orEmpty())

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private object ScrollFrameRateApi35 {
    fun request(view: View) {
        val rate = peakFrameRateOf(view)
        view.requestedFrameRate = rate
        AndroidAgentLogger.info(
            "FrameRateDiag hold=on requested=$rate readBack=${view.requestedFrameRate} " +
                "attached=${view.isAttachedToWindow}",
        )
    }

    fun clear(view: View) {
        view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        AndroidAgentLogger.info("FrameRateDiag hold=off readBack=${view.requestedFrameRate}")
    }
}

// 惯性结束后列表还会有一两帧收尾，跟底和展开动画也常在这段里接着动。
internal const val SCROLL_FRAME_RATE_TAIL_MS = 300L
internal const val FALLBACK_PEAK_FRAME_RATE = 120f
