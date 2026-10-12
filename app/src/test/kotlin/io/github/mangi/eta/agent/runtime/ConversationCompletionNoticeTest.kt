package io.github.mangi.eta.agent.runtime

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationCompletionNoticeTest {
    @Test fun normalAndVirtualSuccessBothCompleteButFailureAndStopDoNot() {
        val normal = AgentRuntimeWire.RunResult("normal", true, "answer")
        assertTrue(ConversationCompletionNotice.confirmed(normal))
        assertTrue(ConversationCompletionNotice.confirmed(normal.copy(virtualDeliveryCompleted = true)))
        assertTrue(ConversationCompletionNotice.confirmed(normal.copy(content = "")))
        assertFalse(ConversationCompletionNotice.confirmed(normal.copy(ok = false)))
        assertFalse(ConversationCompletionNotice.confirmed(normal.copy(error = "uncertain")))
        assertFalse(ConversationCompletionNotice.confirmed(normal, wasStopped = true))
    }

    @Test fun titleIsNormalizedAndBlankHasFallback() {
        assertEquals("整理照片 · 已完成", ConversationCompletionNotice.text("  整理照片  "))
        assertEquals("整理 照片 · 已完成", ConversationCompletionNotice.text("整理\n\t照片"))
        assertEquals("未命名会话 · 已完成", ConversationCompletionNotice.text(" \n "))
    }

    @Test fun liveAndRecoverySameRunOnlyShowOnceAndOtherConversationsStillShow() {
        ShadowToast.reset()
        val context = RuntimeEnvironment.getApplication()
        val run = "complete-${System.nanoTime()}"
        val result = AgentRuntimeWire.RunResult(run, true, "answer")
        ConversationCompletionNotice.show(context, run, "后台会话", result)
        ConversationCompletionNotice.show(context, run, "切页后的其它标题", result)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("后台会话 · 已完成", ShadowToast.getTextOfLatestToast())
        val other = run + "-other"
        ConversationCompletionNotice.show(context, other, "另一个会话", result.copy(runId = other))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, ShadowToast.shownToastCount())
        assertEquals("另一个会话 · 已完成", ShadowToast.getTextOfLatestToast())
    }

    @Test fun failedStoppedAndBlankRunDoNotShowOrConsumeSuccessfulNotice() {
        ShadowToast.reset()
        val context = RuntimeEnvironment.getApplication()
        val run = "stop-${System.nanoTime()}"
        val success = AgentRuntimeWire.RunResult(run, true, "done")
        ConversationCompletionNotice.show(context, run, "会话", success.copy(ok = false, error = "已停止"))
        ConversationCompletionNotice.show(context, run, "会话", success, wasStopped = true)
        ConversationCompletionNotice.show(context, "", "会话", success)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, ShadowToast.shownToastCount())
        ConversationCompletionNotice.show(context, run, "会话", success)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ShadowToast.shownToastCount())
    }
}
