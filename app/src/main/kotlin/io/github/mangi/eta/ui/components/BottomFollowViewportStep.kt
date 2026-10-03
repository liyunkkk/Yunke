package io.github.mangi.eta.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Resolves how far the auto-follow controller should scroll the chat viewport for a single step.
 *
 * The controller already glides the viewport by [smoothStepPx]. That smooth motion is sized for
 * the drawn content, but when the drawn tail overflows the real draw buffer the smooth step can be
 * far smaller than the true deficit, so the very bottom stays clipped for many frames. This helper
 * only tops the smooth step up to the part of the measured overflow that the after-content padding
 * buffer cannot absorb. Whatever still fits inside the buffer keeps moving smoothly.
 *
 * The result never exceeds the measured overflow, so a caller that keeps its existing bounds checks
 * cannot be asked to scroll past the real deficit.
 *
 * This is a pure function: it performs no scrolling, does not touch velocity and keeps no state.
 * Gesture / navigation / should-follow guards stay with the caller.
 *
 * @param smoothStepPx the normal per-frame smooth follow distance in px. Negative values contribute
 *   no movement.
 * @param measuredOverflowPx the measured tail overflow past the viewport in px, or `null` when the
 *   exact tail is not known yet. When unknown we deliberately do not invent an estimated jump and
 *   only return the normal smooth step.
 * @param afterContentPaddingPx the padding drawn after the content, i.e. the draw buffer that can
 *   hold overflow without visible clipping. Zero or negative values contribute no budget.
 * @return the distance in px to scroll this step; never negative and never larger than the measured
 *   overflow.
 */
internal fun resolveBottomFollowViewportStep(
    smoothStepPx: Float,
    measuredOverflowPx: Int?,
    afterContentPaddingPx: Int,
): Float {
    val smooth = max(smoothStepPx, 0f)
    val overflow = measuredOverflowPx ?: return smooth
    val distance = max(overflow, 0)
    val budget = max(afterContentPaddingPx, 0)
    val requiredCompensation = max(distance - budget, 0)
    return min(distance.toFloat(), max(smooth, requiredCompensation.toFloat()))
}

/**
 * LazyList 的滚动偏移是整数像素，平滑步长却是小数。
 * 小数滚动和绘制上提用的整数超出量对不齐时，卡片底边会来回跳大约 1 像素。
 * 这里只滚动整像素；不足 1 像素但确实还有距离时前进 1 像素，避免停在描边外侧。
 */
internal fun snapFollowScrollStep(stepPx: Float, remainingPx: Float): Float {
    if (!stepPx.isFinite() || !remainingPx.isFinite() || stepPx <= 0f || remainingPx <= 0f) return 0f
    val whole = stepPx.roundToInt().toFloat().coerceIn(0f, remainingPx)
    return if (whole == 0f && remainingPx >= 1f) 1f else whole
}

/**
 * Observe the measured tail in placement, after measuring the list, rather than in the layer's
 * parameter callback. A streaming child may remeasure while the viewport keeps the same size;
 * its layoutInfo change must request placement before the new content is drawn under the rest clip.
 *
 * Measurement constraints, reported size and the child's layout position are unchanged. Only the
 * layer moves, so drawing, pointer hit testing and semantic bounds share the same transform. The
 * caller still caps [overflowPx] to the real draw buffer; unknown tails keep the last measured lift.
 */
internal fun Modifier.bottomFollowLayer(
    shouldLift: Boolean,
    heldLiftPx: IntArray,
    overflowPx: () -> Int?,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val lift = nextHeldTailLift(
            shouldLift = shouldLift,
            overflowPx = if (shouldLift) overflowPx() else null,
            heldPx = heldLiftPx[0],
        )
        heldLiftPx[0] = lift
        placeable.placeWithLayer(0, 0) { translationY = -lift.toFloat() }
    }
}
