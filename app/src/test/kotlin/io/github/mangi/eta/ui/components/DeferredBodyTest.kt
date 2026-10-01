package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 点击展开时正文推迟一帧组合；非点击进入（滚回可视区、配置恢复）必须一次到位。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeferredBodyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tapExpansionComposesBodyOnTheFollowingFrame() {
        val readyFrames = mutableListOf<Boolean>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box { readyFrames += rememberDeferredBody(deferOneFrame = true) }
        }
        assertEquals(listOf(false), readyFrames)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(readyFrames.last())
        assertFalse(readyFrames.first())
    }

    @Test fun nonTapEntryIsReadyImmediately() {
        var firstValue: Boolean? = null
        compose.setContent {
            Box {
                val ready = rememberDeferredBody(deferOneFrame = false)
                if (firstValue == null) firstValue = ready
            }
        }
        compose.waitForIdle()
        assertEquals(true, firstValue)
    }

    @Test fun laterFlagChangesDoNotReDefer() {
        val defer = mutableStateOf(false)
        val compositionsNotReady = mutableIntStateOf(0)
        compose.setContent {
            Box { if (!rememberDeferredBody(deferOneFrame = defer.value)) compositionsNotReady.intValue++ }
        }
        compose.runOnIdle { defer.value = true }
        compose.waitForIdle()
        assertEquals(0, compositionsNotReady.intValue)
    }
}
