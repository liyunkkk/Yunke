package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionNotificationTrackerTest {
    @Test
    fun testResolveToolDisplayName() {
        assertEquals("分析屏幕内容", AgentExecutionNotificationTracker.resolveToolDisplayName("observe_screen"))
        assertEquals("分析屏幕内容", AgentExecutionNotificationTracker.resolveToolDisplayName("capture_screen"))
        assertEquals("点击屏幕", AgentExecutionNotificationTracker.resolveToolDisplayName("tap"))
        assertEquals("滑动屏幕", AgentExecutionNotificationTracker.resolveToolDisplayName("swipe"))
        assertEquals("输入文本", AgentExecutionNotificationTracker.resolveToolDisplayName("input_text"))
        assertEquals("执行终端命令", AgentExecutionNotificationTracker.resolveToolDisplayName("terminal"))
        assertEquals("执行终端命令", AgentExecutionNotificationTracker.resolveToolDisplayName("shell"))
        assertEquals("执行终端命令", AgentExecutionNotificationTracker.resolveToolDisplayName("run_command"))
        assertEquals("读取文件", AgentExecutionNotificationTracker.resolveToolDisplayName("read_file"))
        assertEquals("写入文件", AgentExecutionNotificationTracker.resolveToolDisplayName("write_file"))
        assertEquals("联网检索", AgentExecutionNotificationTracker.resolveToolDisplayName("web_search"))
        assertEquals("custom_tool", AgentExecutionNotificationTracker.resolveToolDisplayName("custom_tool"))
    }

    @Test
    fun testExtractSingleLinePreview() {
        val single = "这是简单的一句话"
        assertEquals("这是简单的一句话", AgentExecutionNotificationTracker.extractSingleLinePreview(single))

        val multiline = "第一行思路\n第二行思考\n最后一步推导完成"
        assertEquals("最后一步推导完成", AgentExecutionNotificationTracker.extractSingleLinePreview(multiline))

        val multilineTrailingBlanks = "第一行思路\n正在分析问题\n\n   \n"
        assertEquals("正在分析问题", AgentExecutionNotificationTracker.extractSingleLinePreview(multilineTrailingBlanks))
    }

    @Test
    fun testExtractSingleLinePreviewTruncates() {
        val longText = "a".repeat(100)
        val preview = AgentExecutionNotificationTracker.extractSingleLinePreview(longText, maxLength = 30)
        assertEquals(31, preview.length)
        assertTrue(preview.startsWith("…"))
    }

    @Test
    fun testFormatToolPreview() {
        val withCmd = AgentExecutionNotificationTracker.formatToolPreview(
            command = "ls -la /sdcard",
            argsPreview = "{\"cmd\": \"ls -la /sdcard\"}",
        )
        assertEquals("ls -la /sdcard", withCmd)

        val withoutCmd = AgentExecutionNotificationTracker.formatToolPreview(
            command = null,
            argsPreview = "path: /sdcard/Download",
        )
        assertEquals("path: /sdcard/Download", withoutCmd)
    }

    @Test
    fun testExtractTail() {
        val text = "0123456789".repeat(10)
        val tail = AgentExecutionNotificationTracker.extractTail(text, maxLength = 20)
        assertEquals(21, tail.length)
        assertTrue(tail.startsWith("…"))
        assertTrue(tail.endsWith("0123456789"))
    }
}
