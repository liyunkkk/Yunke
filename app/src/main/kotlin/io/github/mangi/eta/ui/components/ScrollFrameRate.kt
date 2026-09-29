package io.github.mangi.eta.ui.components

import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 用户拖动和惯性滑动期间，向系统要一个明确的最高帧率，停下后再保留 [SCROLL_FRAME_RATE_TAIL_MS]。
 *
 * 实测（vivo，Android 15）：惯性变慢时系统按内容速度把 Eta 的出帧从 120 逐级降到 90、72、60，
 * 每切一次都掉帧，看起来就是“卡一下再恢复”。锁定显示器 120Hz 也拦不住，因为降的是应用自己的
 * 出帧速率（每两次刷新出一帧）。Compose 位移时投的 FrameRateCategory.High 在这台机器上压不住
 * 这套速度判断，所以直接给根 View 设明确帧率。只在滑动期间生效，其余时间交回系统默认。
 *
 * [userScrolling] 只在 snapshotFlow 里读，滑动开始和结束不会让调用方重组。
 */
@Composable
internal fun HoldPeakFrameRateWhileScrolling(userScrolling: () -> Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val view = LocalView.current
    val currentUserScrolling by rememberUpdatedState(userScrolling)
    var hold by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { currentUserScrolling() }
            .collectLatest { scrolling ->
                if (scrolling) {
                    hold = true
                } else {
                    delay(SCROLL_FRAME_RATE_TAIL_MS)
                    hold = false
                }
            }
    }
    DisposableEffect(view, hold) {
        if (!hold) return@DisposableEffect onDispose {}
        ScrollFrameRateApi35.request(view)
        onDispose { ScrollFrameRateApi35.clear(view) }
    }
}

/** 取显示器支持的最高刷新率；拿不到时用 [FALLBACK_PEAK_FRAME_RATE]。 */
internal fun pickPeakFrameRate(supportedRates: List<Float>): Float =
    supportedRates.filter { it.isFinite() && it > 0f }.maxOrNull() ?: FALLBACK_PEAK_FRAME_RATE

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private object ScrollFrameRateApi35 {
    fun request(view: View) {
        val rates = view.display?.supportedModes?.map { it.refreshRate }.orEmpty()
        view.requestedFrameRate = pickPeakFrameRate(rates)
    }

    fun clear(view: View) {
        view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    }
}

// 惯性结束后列表还会有一两帧收尾，跟底和展开动画也常在这段里接着动。
internal const val SCROLL_FRAME_RATE_TAIL_MS = 300L
internal const val FALLBACK_PEAK_FRAME_RATE = 120f
