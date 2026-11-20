package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 统一的 SSE 帧读取器：
 * - 支持多行 `data:` 以 `\n` 拼接
 * - 空行触发事件派发
 * - `:` 开头视为注释行
 * - `onEvent` 返回 `false` 时提前终止读取
 */
internal fun readProviderSse(
    stream: InputStream,
    runController: AgentRunController,
    dispatchPartialFrameAtEof: Boolean = true,
    onEvent: (eventName: String, data: String) -> Boolean,
) {
    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
        var currentEvent = ""
        val currentData = StringBuilder()

        fun dispatchFrame(): Boolean {
            if (currentEvent.isEmpty() && currentData.isEmpty()) return true
            val eventName = currentEvent
            val data = currentData.toString().removeSuffix("\n")
            currentEvent = ""
            currentData.setLength(0)
            if (data.isBlank()) return true
            return onEvent(eventName, data)
        }

        while (true) {
            runController.throwIfCancelled()
            val rawLine = reader.readLine() ?: break
            val line = rawLine.trimEnd('\r')

            if (line.isEmpty()) {
                if (!dispatchFrame()) return
                continue
            }

            if (line.startsWith(":")) continue

            val separatorIndex = line.indexOf(':')
            val field = if (separatorIndex >= 0) line.substring(0, separatorIndex) else line
            val rawValue = if (separatorIndex >= 0) line.substring(separatorIndex + 1) else ""
            val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue

            when (field) {
                "event" -> currentEvent = value.trim()
                "data" -> currentData.append(value).append('\n')
            }
        }

        runController.throwIfCancelled()
        if (dispatchPartialFrameAtEof) {
            dispatchFrame()
        }
    }
}
