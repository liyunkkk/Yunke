package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatScrollPolicyTest {
    @Test
    fun networkCompletionKeepsFollowingUntilRenderedTailSettles() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun draggingInterruptsCompletionFollowing() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = true,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completionDoesNotPullReaderBackFromHistory() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = false,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completedContentExpansionDoesNotFollowBottom() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun restingTailRemainsClippedAfterFollowEnds() {
        // 停止跟底之后的静止尾部仍然要被裁剪，否则新内容会画进输入框上方。
        assertTrue(
            shouldClipChatTail(
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = false,
            )
        )
    }

    @Test
    fun manualScrollingReleasesComposerClip() {
        assertFalse(
            shouldClipChatTail(
                isUserScrolling = true,
                isUserDragging = false,
                navigationActive = false,
            )
        )
        assertFalse(
            shouldClipChatTail(
                isUserScrolling = false,
                isUserDragging = true,
                navigationActive = false,
            )
        )
    }

    @Test
    fun messageNavigationReleasesComposerClip() {
        assertFalse(
            shouldClipChatTail(
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = true,
            )
        )
    }

    @Test
    fun interactionInertiaAndNavigationOnlyReleaseTheClipWhileActive() {
        // 惯性滑动：手指已经抬起，但列表仍在移动。
        assertFalse(
            shouldClipChatTail(
                isUserScrolling = true,
                isUserDragging = false,
                navigationActive = false,
            )
        )
        // 手指按住拖动。
        assertFalse(
            shouldClipChatTail(
                isUserScrolling = false,
                isUserDragging = true,
                navigationActive = false,
            )
        )
        // 导航跳转同时动用了滚动与导航。
        assertFalse(
            shouldClipChatTail(
                isUserScrolling = true,
                isUserDragging = false,
                navigationActive = true,
            )
        )
        // 手势与导航结束后恢复静止裁剪。
        assertTrue(
            shouldClipChatTail(
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = false,
            )
        )
    }

    @Test
    fun tinyDragAtBottomStopsFollowingYetStillClipsTheRestingTail() {
        val afterDrag = resolveKeepBottomAnchored(
            current = true,
            isUserDragging = true,
            isAtBottom = true,
            hasLeftBottom = false,
        )
        assertFalse(afterDrag)
        val afterRelease = resolveKeepBottomAnchored(
            current = afterDrag,
            isUserDragging = false,
            isAtBottom = true,
            hasLeftBottom = false,
        )
        assertFalse(afterRelease)
        // 松手恢复裁剪，但不能重新启用跟底或上提来改变历史阅读位置。
        assertTrue(
            shouldClipChatTail(
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = false,
            )
        )
        val following = resolveBottomFollowEnabled(
            isStreaming = true,
            keepBottomAnchored = afterRelease,
            isUserDragging = false,
            isBottomSettling = true,
        )
        assertFalse(following)
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following, tailOverflowPx = 151))
    }

    @Test
    fun restingClipIsNotGatedByStreamingOrAnchorState() {
        // 非流式（且已经停止跟底）时静止尾部同样裁剪；裁剪决策只接收交互状态，
        // 不再接收 streaming / anchor 输入，因此两者都无法再关闭裁剪。
        assertTrue(
            shouldClipChatTail(
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = false,
            )
        )
    }

    @Test
    fun streamingTailGrowthFollowsBottom() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationDoesNotUseStreamingInitialJump() {
        assertFalse(
            shouldRequestInitialBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationSnapsToBottomOnOpen() {
        assertTrue(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = true,
            )
        )
    }

    @Test
    fun emptyConversationDoesNotSnapToBottom() {
        assertFalse(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = false,
            )
        )
    }

    @Test
    fun scrollToMessageSkipsConversationBottomSnap() {
        assertFalse(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = true,
                scrollToMessageId = "msg-1",
            )
        )
    }

    @Test
    fun conversationBottomSnapAlignsLastItemWithoutAnimation() {
        assertEquals(
            BottomFollowDecision(scrollByPx = -120),
            resolveConversationBottomSnap(
                bottomItemIndex = 8,
                lastVisibleIndex = 8,
                lastVisibleBottom = 880,
                viewportEnd = 1000,
            ),
        )
    }

    @Test
    fun conversationBottomSnapRequestsLastItemWhenNotVisible() {
        assertEquals(
            BottomFollowDecision(requestIndex = 8),
            resolveConversationBottomSnap(
                bottomItemIndex = 8,
                lastVisibleIndex = 2,
                lastVisibleBottom = 400,
                viewportEnd = 1000,
            ),
        )
    }

    @Test
    fun streamingConversationRequestsInitialBottom() {
        assertTrue(
            shouldRequestInitialBottom(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun contentGrowthDoesNotDisableBottomFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = false,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingAwayFromBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingWhileAtBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = true,
            )
        )
    }

    @Test
    fun tinyDragAtBottomDoesNotResumeFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = false,
            )
        )
    }

    @Test
    fun landingOnBottomAfterUserScrollEnablesFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = true,
            )
        )
    }

    @Test
    fun reachingBottomEnablesFollowingAgain() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = true,
            )
        )
    }

    @Test
    fun streamingScrollableListDoesNotPinFromBottom() {
        assertFalse(shouldPinConversationToBottom(isStreaming = true, isScrollable = true))
        assertTrue(shouldPinConversationToBottom(isStreaming = true, isScrollable = false))
        assertTrue(shouldPinConversationToBottom(isStreaming = false, isScrollable = true))
    }

    @Test
    fun growingTailOnlyScrollsByTheOverflowDistance() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 24),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = 1024,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test
    fun missingBottomSentinelUsesBoundedSmoothFollow() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 1000),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = null,
                viewportEnd = 1000,
                lastVisibleIndex = 6,
            ),
        )
    }

    @Test
    fun disabledFollowingNeverMovesTheList() {
        assertEquals(
            BottomFollowDecision(),
            resolveBottomFollowDecision(
                enabled = false,
                bottomItemIndex = 8,
                sentinelBottom = 1100,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test fun hiddenSentinelUsesViewportBoundAndNeverRequestsAnIndex() {
        val decision = resolveBottomFollowDecision(true, 20, null, 600, 4, viewportSizePx = 800)
        assertEquals(BottomFollowDecision(scrollByPx = 800), decision)
        assertEquals(null, decision.requestIndex)
    }

    @Test fun reachedPhysicalEndStopsEvenWithStaleOverflow() {
        assertEquals(BottomFollowDecision(), resolveBottomFollowDecision(true, 20, 900, 600, 20, canScrollForward = false))
    }

    @Test fun emptyListDoesNotStartFollowing() {
        assertEquals(BottomFollowDecision(), resolveBottomFollowDecision(true, 0, null, 600, null))
    }

    @Test fun initialPositionWaitsForContentButAbandonsOnUserNavigation() {
        assertFalse(InitialBottomPosition(0, true, true, false).ready)
        assertFalse(InitialBottomPosition(8, false, true, false).ready)
        assertTrue(InitialBottomPosition(8, true, true, false).shouldPosition)
        assertTrue(InitialBottomPosition(0, false, true, true).ready)
        assertFalse(InitialBottomPosition(8, true, true, true).shouldPosition)
        assertFalse(InitialBottomPosition(8, true, false, false).shouldPosition)
    }

    @Test fun tallSingleItemKeepsPublishingDistanceAfterEachViewport() {
        // Emulate snapshotFlow's equality gate while a 4-screen item stays at
        // the same index. No new network text arrives during the entire drain.
        val motion = BottomFollowMotion()
        var position = 0f
        val end = 3200f
        var pending = 0f
        var previous: BottomFollowLayout? = null
        var requests = 0
        for (frame in 0..1800) {
            val remaining = end - position
            val layout = BottomFollowLayout(
                enabled = true, bottomItemIndex = 8,
                sentinelBottom = if (remaining <= 800f) (800f + remaining).toInt() else null,
                viewportEnd = 800, lastVisibleIndex = if (remaining <= 800f) 8 else 7,
                viewportSizePx = 800, canScrollForward = remaining > 0f,
                lastVisibleOffset = -position.toInt(),
            )
            if (layout != previous) {
                previous = layout
                val decision = resolveBottomFollowDecision(layout.enabled, layout.bottomItemIndex,
                    layout.sentinelBottom, layout.viewportEnd, layout.lastVisibleIndex,
                    layout.viewportSizePx, layout.canScrollForward)
                assertEquals(null, decision.requestIndex)
                pending = decision.scrollByPx.toFloat()
                requests++
            }
            val moved = motion.step(pending, frame * 16_666_667L, 1f)
            position += moved
            pending = (pending - moved).coerceAtLeast(0f)
        }
        assertTrue("fallback must remain live beyond the first viewport", position >= end - 1.1f)
        assertTrue(requests > 10)
    }

    @Test
    fun followLagLiftsTailBackToRestLine() {
        assertEquals(FollowTailLag(37f), resolveFollowTailLag(following = true, tailOverflowPx = 37))
    }

    @Test
    fun followLagIgnoresTailAtOrAboveRestLine() {
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following = true, tailOverflowPx = 0))
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following = true, tailOverflowPx = -12))
    }

    @Test
    fun notFollowingReaderNeverLiftsTheTail() {
        // 已经停止跟底：无论尾部越线多少（或未知）都不再上提，避免把读者拉回底部。
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following = false, tailOverflowPx = 80))
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following = false, tailOverflowPx = null))
    }

    @Test
    fun invisibleTailFallsBackToRestLineClip() {
        val lag = resolveFollowTailLag(following = true, tailOverflowPx = null)
        assertTrue(lag.unknown)
        assertEquals(0f, lag.liftPx, 0f)
    }

    @Test
    fun tailBottomFallsBackToTheLastContentItemWhenTheSentinelIsPushedOut() {
        // 哨兵可见时直接用哨兵。
        assertEquals(900, resolveTailBottomPx(900, 11, 900, totalItems = 12))
        // 展开把哨兵挤出可视区，最后一段内容（倒数第二项）还可见：用它的下沿。
        assertEquals(1400, resolveTailBottomPx(null, 10, 1400, totalItems = 12))
        // 看到的只是更靠上的内容：尾部位置未知。
        assertNull(resolveTailBottomPx(null, 9, 1400, totalItems = 12))
        assertNull(resolveTailBottomPx(null, null, null, totalItems = 12))
        assertNull(resolveTailBottomPx(null, 0, 100, totalItems = 1))
    }

    @Test
    fun userInputScrollWithoutAFingerDownDoesNotCountAsUserScrolling() {
        assertTrue(isUserScrollGesture(pointerDown = true))
        assertFalse(isUserScrollGesture(pointerDown = false))
    }

    @Test
    fun compactStackKeepsOnlyClassAndMethodNames() {
        val frames = arrayOf(
            StackTraceElement("self.Frame", "skipped", null, 1),
            StackTraceElement("kotlin.coroutines.Continuation", "resume", null, 1),
            StackTraceElement("a.b.C\$1", "onPreScroll", null, 1),
            StackTraceElement("x.Y", "run secret", null, 1),
        )
        assertEquals("a.b.C\$1.onPreScroll<x.Y.run", compactStack(frames))
    }

    @Test
    fun drawnTailOverflowIsMeasuredAfterTheLift() {
        assertEquals(0, resolveTailDrawnOverflow(1200, 1000, 200))
        assertEquals(150, resolveTailDrawnOverflow(1150, 1000, 0))
        assertEquals(-20, resolveTailDrawnOverflow(980, 1000, 0))
        assertNull(resolveTailDrawnOverflow(null, 1000, 0))
    }

    @Test
    fun expansionGrowsFromTheBottomOnlyWhenTheBottomStaysPut() {
        // 跟底上提：尾部停在静止线。
        assertTrue(resolveExpansionHoldsBottom(following = true, arrangedToBottom = false, listScrollable = true))
        // 不满一屏贴底排列：列表往上长。
        assertTrue(resolveExpansionHoldsBottom(following = false, arrangedToBottom = true, listScrollable = false))
        // 满屏但没在跟底：首个可见项不动，内容往下长。
        assertFalse(resolveExpansionHoldsBottom(following = false, arrangedToBottom = true, listScrollable = true))
        assertFalse(resolveExpansionHoldsBottom(following = false, arrangedToBottom = false, listScrollable = false))
    }
}
