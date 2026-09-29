package io.github.mangi.eta.ui.components

import kotlin.math.max
import kotlin.math.min

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
