package io.github.mangi.eta.agent.translation

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelRetry
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.runtime.AgentExecutionPhase
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentExecutionState
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 屏幕翻译控制器：
 * 屏幕端侧全屏截屏 -> Google ML Kit 离线 OCR 提取像素坐标与采样底色 -> 几何降噪与聚合 ->
 * 批量翻译（Provider 直调）-> 全屏像素级自适应贴合渲染。
 */
internal object ScreenTranslationController {
    private const val TAG_PREFIX = "ScreenTranslation"
    private const val LEASE_ID = "screen_translation"

    /** 单次采集的最大节点数（降级备用）。 */
    private const val MAX_NODES = 120

    /** 参与翻译的单块文本最小长度。 */
    private const val MIN_TEXT_LENGTH = 1

    /** 单次批量翻译的文本总量上限（字符），防止整屏超长文本一次撑爆请求。 */
    private const val MAX_BATCH_CHARS = 6000

    /** 聚合：同一行内相邻块合并的垂直容差（dp）。 */
    private const val LINE_MERGE_GAP_DP = 6f

    /** 缓存上限：超过后清空重建，防止长会话内存增长。 */
    private const val CACHE_MAX_ENTRIES = 512

    /** 内容变化后的采集防抖（ms）。 */
    private const val CAPTURE_DEBOUNCE_MS = 350L

    private val started = AtomicBoolean(false)
    private val renderPending = AtomicBoolean(false)
    private val frameSeq = AtomicLong(0)
    private val activeTranslationCount = AtomicLong(0)
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var mainHandler: Handler? = null
    private var appContext: Context? = null

    /** 译文缓存：文本指纹 -> 译文。 */
    private val translationCache = ConcurrentHashMap<String, String>()

    private val isTranslating = AtomicBoolean(false)

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        mainHandler = Handler(android.os.Looper.getMainLooper())
        val thread = HandlerThread("eta-screen-translation").apply { start() }
        workerThread = thread
        workerHandler = Handler(thread.looper)
        isTranslating.set(false)
        activeTranslationCount.set(0)

        ScreenTranslationOverlayService.show(context)
        AndroidAgentLogger.info("$TAG_PREFIX started")
    }

    fun stop(context: Context) {
        if (!started.compareAndSet(true, false)) return
        isTranslating.set(false)
        workerHandler?.removeCallbacksAndMessages(null)
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        mainHandler = null
        appContext = null
        translationCache.clear()
        activeTranslationCount.set(0)

        // 释放灵动岛与前台保活
        AgentExecutionService.release(LEASE_ID)
        AgentExecutionService.resetExecutionState()
        ScreenTranslationOverlayService.hide(context)
        AndroidAgentLogger.info("$TAG_PREFIX stopped")
    }

    fun isRunning(): Boolean = started.get()

    /** 当前是否正在抓取或翻译中。 */
    fun isTranslating(): Boolean = isTranslating.get()

    /** 当前是否已有活跃译文展示中。 */
    fun hasActiveTranslations(): Boolean = activeTranslationCount.get() > 0

    /** 一键取消/清空译文层并恢复待命状态。 */
    fun clearAndStop(context: Context) {
        isTranslating.set(false)
        activeTranslationCount.set(0)
        workerHandler?.removeCallbacksAndMessages(null)
        clearOverlay()
        AgentExecutionService.release(LEASE_ID)
        AgentExecutionService.resetExecutionState()
        updateOverlayStatus(context.getString(io.github.mangi.eta.R.string.screen_translation_btn_translate))
    }

    /** 内容变化触发重采集：单次模式下不自激。 */
    fun onScreenContentChanged() {
        // 单次按需触发
    }

    /** 手动触发单次屏幕翻译。 */
    fun requestRefresh() {
        val context = appContext ?: return
        if (isTranslating.get()) return
        isTranslating.set(true)
        updateOverlayStatus(context.getString(io.github.mangi.eta.R.string.screen_translation_translating))
        AgentExecutionService.acquire(context, LEASE_ID) {
            stop(context)
        }
        scheduleCapture(delayMs = 0)
    }

    private val pendingCapture = Runnable { runCapture() }

    private fun scheduleCapture(delayMs: Long = CAPTURE_DEBOUNCE_MS) {
        val handler = workerHandler ?: return
        handler.removeCallbacks(pendingCapture)
        if (delayMs <= 0) {
            handler.post(pendingCapture)
        } else {
            handler.postDelayed(pendingCapture, delayMs)
        }
    }

    // ------------------------------------------------------------------
    // 采集与识别（OCR 优先，无障碍降级）
    // ------------------------------------------------------------------

    private fun runCapture() {
        if (!started.get()) return
        val service = AgentAccessibilityService.current() ?: run {
            AndroidAgentLogger.warnThrottled("screen_translation_no_service") {
                "Screen translation capture skipped: accessibility service not connected"
            }
            return
        }

        val seq = frameSeq.incrementAndGet()
        val startedAt = SystemClock.elapsedRealtime()

        // 灵动岛状态通知：正在分析屏幕
        AgentExecutionService.updateExecutionState(
            AgentExecutionState(
                phase = AgentExecutionPhase.TOOL_EXECUTING,
                title = "⚡ 分析屏幕…",
                detail = "正在高精识别屏幕文字内容…",
                showChronometer = true,
                startedAtElapsedRealtime = startedAt,
            ),
        )

        var blocks: List<ScreenTranslationBlock> = emptyList()

        // 1. 优先通过无障碍高保真截图 + Google ML Kit 离线 OCR
        val screenshotResult = runCatching {
            service.captureScreenshotExcludingOverlays(
                excludedPackages = setOf(SELF_PACKAGE, SYSTEM_UI_PACKAGE),
            )
        }.getOrNull()

        val bitmap = screenshotResult?.bitmap
        if (bitmap != null) {
            try {
                blocks = runCatching {
                    runBlocking {
                        ScreenOcrEngine.recognize(bitmap)
                    }
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("screen_translation_ocr_failed") {
                        "Screen translation OCR failed: ${throwable.javaClass.simpleName}"
                    }
                    emptyList()
                }
            } finally {
                runCatching { bitmap.recycle() }
            }
        }

        // 2. 截屏或 OCR 结果为空时，降级使用无障碍节点树
        if (blocks.isEmpty()) {
            val snapshot = runCatching { service.captureNodeSnapshot(MAX_NODES) }
                .getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("screen_translation_capture_failed") {
                        "Screen translation capture failed: ${throwable.javaClass.simpleName}"
                    }
                    null
                }
            val packageName = snapshot?.packageName.orEmpty()
            if (packageName.isBlank() || packageName == SELF_PACKAGE || packageName == SYSTEM_UI_PACKAGE) {
                return
            }
            val rawNodes = snapshot?.nodes.orEmpty()
            if (rawNodes.isEmpty()) {
                clearOverlay()
                return
            }
            val density = appContext?.resources?.displayMetrics?.density ?: 1f
            blocks = aggregateNodes(rawNodes, density)
        }

        if (blocks.isEmpty()) {
            clearOverlay()
            return
        }

        AndroidAgentLogger.debug {
            "$TAG_PREFIX action=capture seq=$seq blocks=${blocks.size} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}"
        }

        translateAndRender(blocks, seq)
    }

    private fun aggregateNodes(
        nodes: List<AgentAccessibilityService.UiNode>,
        density: Float,
    ): List<ScreenTranslationBlock> {
        val rawCandidates = ArrayList<ScreenTranslationBlock>(nodes.size)
        for (node in nodes) {
            if (node.password || !node.enabled || node.editable) continue
            val text = pickNodeText(node) ?: continue
            if (text.length < MIN_TEXT_LENGTH || isNoiseText(text)) continue
            val bounds = node.bounds
            if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) continue
            rawCandidates.add(ScreenTranslationBlock(source = text, boundsInScreen = Rect(bounds)))
        }
        if (rawCandidates.isEmpty()) return emptyList()

        val leafCandidates = ArrayList<ScreenTranslationBlock>(rawCandidates.size)
        for (i in 0 until rawCandidates.size) {
            val a = rawCandidates[i]
            val aBounds = a.boundsInScreen
            var isParentContainer = false
            for (j in 0 until rawCandidates.size) {
                if (i == j) continue
                val b = rawCandidates[j]
                val bBounds = b.boundsInScreen
                if (aBounds.contains(bBounds) &&
                    (aBounds.width() * aBounds.height()) > (bBounds.width() * bBounds.height() * 1.25f)
                ) {
                    isParentContainer = true
                    break
                }
            }
            if (!isParentContainer) {
                leafCandidates.add(a)
            }
        }

        val spatialDeduplicated = ArrayList<ScreenTranslationBlock>(leafCandidates.size)
        val tolerance = (5 * density).toInt()
        for (block in leafCandidates) {
            val duplicate = spatialDeduplicated.any { existing ->
                abs(existing.boundsInScreen.centerX() - block.boundsInScreen.centerX()) <= tolerance &&
                    abs(existing.boundsInScreen.centerY() - block.boundsInScreen.centerY()) <= tolerance &&
                    abs(existing.boundsInScreen.width() - block.boundsInScreen.width()) <= tolerance * 2
            }
            if (!duplicate) {
                spatialDeduplicated.add(block)
            }
        }

        if (spatialDeduplicated.size <= 1) return spatialDeduplicated
        return mergeInline(spatialDeduplicated, density)
            .sortedWith(compareBy({ it.boundsInScreen.top }, { it.boundsInScreen.left }))
    }

    private fun pickNodeText(node: AgentAccessibilityService.UiNode): String? {
        val text = node.text.trim()
        if (text.isNotEmpty()) return text
        val desc = node.desc.trim()
        if (desc.isNotEmpty()) return desc
        return null
    }

    private fun isNoiseText(text: String): Boolean {
        var letters = 0
        for (ch in text) {
            if (ch.isLetter()) letters++
        }
        return letters == 0
    }

    private fun mergeInline(
        blocks: List<ScreenTranslationBlock>,
        density: Float,
    ): List<ScreenTranslationBlock> {
        if (blocks.size <= 1) return blocks
        val gap = (LINE_MERGE_GAP_DP * density).toInt()
        val sorted = blocks.sortedWith(compareBy({ it.boundsInScreen.top }, { it.boundsInScreen.left }))
        val rows = ArrayList<ScreenTranslationBlock>(blocks.size)
        var current: ScreenTranslationBlock? = null

        for (block in sorted) {
            val head = current
            if (head == null) {
                current = block
                continue
            }
            val sameLine = abs(head.boundsInScreen.centerY() - block.boundsInScreen.centerY()) <= gap &&
                abs(head.boundsInScreen.height() - block.boundsInScreen.height()) <= gap * 1.5f
            val horizontalGap = block.boundsInScreen.left - head.boundsInScreen.right
            val horizontalAdjacent = block.boundsInScreen.left >= head.boundsInScreen.left &&
                horizontalGap in 0..(gap * 1.8f).toInt()
            if (sameLine && horizontalAdjacent) {
                current = head.copy(
                    source = head.source + " " + block.source,
                    boundsInScreen = Rect(
                        head.boundsInScreen.left,
                        minOf(head.boundsInScreen.top, block.boundsInScreen.top),
                        maxOf(head.boundsInScreen.right, block.boundsInScreen.right),
                        maxOf(head.boundsInScreen.bottom, block.boundsInScreen.bottom),
                    ),
                )
            } else {
                rows.add(head)
                current = block
            }
        }
        current?.let(rows::add)
        return rows
    }

    // ------------------------------------------------------------------
    // 翻译
    // ------------------------------------------------------------------

    private fun translateAndRender(blocks: List<ScreenTranslationBlock>, seq: Long) {
        try {
            val workBlocks = blocks.map { it }.toMutableList()

            val pending = ArrayList<Pair<Int, String>>()
            blocks.forEachIndexed { index, block ->
                val fp = fingerprint(block.source)
                val cached = translationCache[fp]
                if (cached != null) {
                    workBlocks[index] = block.copy(translated = cached)
                } else {
                    pending.add(index to fp)
                }
            }

            if (pending.isEmpty()) {
                activeTranslationCount.set(workBlocks.size.toLong())
                renderOnMain(workBlocks)
                AgentExecutionService.updateExecutionState(
                    AgentExecutionState(
                        phase = AgentExecutionPhase.IDLE,
                        title = "✅ 翻译完成",
                        detail = "已贴合 ${workBlocks.size} 处译文",
                        showChronometer = false,
                    ),
                )
                return
            }

            var batchStart = 0
            while (batchStart < pending.size) {
                if (!started.get()) return
                var chars = 0
                var batchEnd = batchStart
                while (batchEnd < pending.size) {
                    val nextChars = blocks[pending[batchEnd].first].source.length
                    if (chars + nextChars > MAX_BATCH_CHARS && batchEnd > batchStart) break
                    chars += nextChars
                    batchEnd++
                }
                val batch = pending.subList(batchStart, batchEnd)
                val ok = runTranslationBatch(blocks, batch, workBlocks)
                if (!ok) return
                activeTranslationCount.set(workBlocks.count { it.translated != null }.toLong())
                renderOnMain(workBlocks)
                batchStart = batchEnd
            }

            AgentExecutionService.updateExecutionState(
                AgentExecutionState(
                    phase = AgentExecutionPhase.IDLE,
                    title = "✅ 翻译完成",
                    detail = "已贴合 ${workBlocks.size} 处译文",
                    showChronometer = false,
                ),
            )
            AndroidAgentLogger.debug {
                "$TAG_PREFIX action=translate_done seq=$seq total=${blocks.size} translated=${pending.size}"
            }
        } finally {
            isTranslating.set(false)
        }
    }

    private fun resolveRuntimeConfig(): AgentModelClient.ModelConfig? {
        val repoConfig = runCatching {
            runBlocking {
                RuntimeConfigRepository.currentRuntimeConfig()
            }
        }.getOrNull()
        if (repoConfig != null && repoConfig.apiKey.isNotBlank()) {
            return repoConfig.copy(
                thinkingEnabled = false,
                reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.OFF,
                reasoningCapabilities = null,
            )
        }
        val clientConfig = runCatching { AgentModelClient.loadConfig() }.getOrNull()
        if (clientConfig != null && clientConfig.apiKey.isNotBlank()) {
            return clientConfig.copy(
                thinkingEnabled = false,
                reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.OFF,
                reasoningCapabilities = null,
            )
        }
        val fallback = repoConfig ?: clientConfig
        return fallback?.copy(
            thinkingEnabled = false,
            reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.OFF,
            reasoningCapabilities = null,
        )
    }

    private fun runTranslationBatch(
        blocks: List<ScreenTranslationBlock>,
        batch: List<Pair<Int, String>>,
        workBlocks: MutableList<ScreenTranslationBlock>,
    ): Boolean {
        val context = appContext ?: return false
        val config = resolveRuntimeConfig() ?: run {
            postOverlayError(context.getString(io.github.mangi.eta.R.string.screen_translation_error_no_key))
            return false
        }
        if (config.apiKey.isBlank()) {
            AndroidAgentLogger.warnThrottled("screen_translation_no_api_key") {
                "Screen translation failed: API key is blank for provider ${config.providerName}"
            }
            postOverlayError(context.getString(io.github.mangi.eta.R.string.screen_translation_error_no_key))
            return false
        }

        updateOverlayStatus(context.getString(io.github.mangi.eta.R.string.screen_translation_translating))
        AgentExecutionService.updateExecutionState(
            AgentExecutionState(
                phase = AgentExecutionPhase.TRANSLATING,
                title = "🌐 智能翻译…",
                detail = "正在直连模型翻译 ${batch.size} 处文本…",
                showChronometer = true,
                startedAtElapsedRealtime = SystemClock.elapsedRealtime(),
            ),
        )

        val provider = ProviderClientFactory.getClient(config)
        val controller = AgentRunController()
        val retry = AgentModelRetry()

        val messages = JSONArray()
        messages.put(systemPrompt(context))
        val items = JSONArray()
        batch.forEach { (index, _) ->
            items.put(
                JSONObject()
                    .put("id", index)
                    .put("text", blocks[index].source),
            )
        }
        val payload = JSONObject().put("items", items)
        messages.put(AgentConversationCodec.userTextMessage(payload.toString()))

        val request = ProviderRequest(
            config = config.copy(
                hostedWebSearchEnabled = false,
                extraBodyJson = "",
                customBody = emptyList(),
                thinkingEnabled = false,
                reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.OFF,
                reasoningCapabilities = null,
            ),
            messages = messages,
            tools = JSONArray(),
        )

        val response = try {
            retry.complete(
                initialRound = 0,
                request = request,
                provider = provider,
                controller = controller,
                onEvent = {},
                onProviderEvent = { _, _ -> },
                discardAttemptReasoning = {},
            ).response
        } catch (failure: Exception) {
            AndroidAgentLogger.warnThrottled("screen_translation_model_failed") {
                "Screen translation model call failed: ${failure.message?.take(120)}"
            }
            postOverlayError(context.getString(io.github.mangi.eta.R.string.screen_translation_error_failed))
            return false
        }

        val content = response.assistantMessage.optString("content").trim()
        if (content.isBlank() || content == "null") {
            postOverlayError(context.getString(io.github.mangi.eta.R.string.screen_translation_error_failed))
            return false
        }

        val parsed = runCatching { parseTranslations(content) }.getOrNull()
        if (parsed == null) {
            AndroidAgentLogger.warnThrottled("screen_translation_parse_failed") {
                "Screen translation response unparseable: ${content.take(80)}"
            }
            postOverlayError(context.getString(io.github.mangi.eta.R.string.screen_translation_error_failed))
            return false
        }

        for ((index, translation) in parsed) {
            if (translation.isBlank()) continue
            val fp = batch.firstOrNull { it.first == index }?.second ?: continue
            translationCache[fp] = translation
            if (translationCache.size > CACHE_MAX_ENTRIES) {
                translationCache.clear()
                translationCache[fp] = translation
            }
            workBlocks[index] = blocks[index].copy(translated = translation)
        }
        updateOverlayStatus(context.getString(io.github.mangi.eta.R.string.screen_translation_close))
        return true
    }

    private fun parseTranslations(content: String): List<Pair<Int, String>>? {
        val cleaned = cleanResponseContent(content)

        // 1. 尝试直接从整体 JSON Array 解析
        val arrayMatch = Regex("""\[\s*\{[\s\S]*\}\s*\]""").find(cleaned)
        if (arrayMatch != null) {
            runCatching { JSONArray(arrayMatch.value) }.getOrNull()?.let { array ->
                val out = extractFromArray(array)
                if (out.isNotEmpty()) return out
            }
        }

        // 2. 尝试从 JSON Object { "items": [...] } 解析
        val objectMatch = Regex("""\{\s*"(?:items|translations|data)"\s*:\s*\[[\s\S]*\]\s*\}""").find(cleaned)
        if (objectMatch != null) {
            runCatching { JSONObject(objectMatch.value) }.getOrNull()?.let { obj ->
                val items = obj.optJSONArray("items") ?: obj.optJSONArray("translations") ?: obj.optJSONArray("data")
                if (items != null) {
                    val out = extractFromArray(items)
                    if (out.isNotEmpty()) return out
                }
            }
        }

        // 3. 正则扫描独立的 {"id": ..., "text": "..."} 块（容错即使外层包裹坏掉或截断）
        val itemPattern = Regex("""\{\s*"id"\s*:\s*(\d+)\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}""")
        val matches = itemPattern.findAll(cleaned).toList()
        if (matches.isNotEmpty()) {
            val out = ArrayList<Pair<Int, String>>(matches.size)
            for (m in matches) {
                val id = m.groupValues[1].toIntOrNull() ?: continue
                val text = unescapeJsonString(m.groupValues[2])
                out.add(id to text)
            }
            if (out.isNotEmpty()) return out
        }

        // 4. 正则反序扫描 {"text": "...", "id": ...}
        val reversedItemPattern = Regex("""\{\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"id"\s*:\s*(\d+)\s*\}""")
        val reversedMatches = reversedItemPattern.findAll(cleaned).toList()
        if (reversedMatches.isNotEmpty()) {
            val out = ArrayList<Pair<Int, String>>(reversedMatches.size)
            for (m in reversedMatches) {
                val text = unescapeJsonString(m.groupValues[1])
                val id = m.groupValues[2].toIntOrNull() ?: continue
                out.add(id to text)
            }
            if (out.isNotEmpty()) return out
        }

        // 5. 按行解析多格式兜底: "0|译文", "0: 译文", "0. 译文", "[0] 译文"
        val linePattern = Regex("""^\s*(?:\[|\()?(\d+)(?:\]|\))?\s*[:|\-\.]\s*(.+)$""")
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Pair<Int, String>>(lines.size)
        for (line in lines) {
            val match = linePattern.matchEntire(line)
            if (match != null) {
                val id = match.groupValues[1].toIntOrNull()
                val text = match.groupValues[2].trim().removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'")
                if (id != null && id >= 0 && text.isNotBlank()) {
                    out.add(id to text)
                }
            }
        }
        return if (out.isNotEmpty()) out else null
    }

    private fun extractFromArray(array: JSONArray): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            if (item != null) {
                val id = item.optInt("id", -1)
                val text = item.optString("text", "")
                if (id >= 0 && text.isNotBlank()) out.add(id to text)
            } else {
                val raw = array.optString(i, "")
                val obj = runCatching { JSONObject(raw) }.getOrNull()
                if (obj != null) {
                    val id = obj.optInt("id", -1)
                    val text = obj.optString("text", "")
                    if (id >= 0 && text.isNotBlank()) out.add(id to text)
                }
            }
        }
        return out
    }

    private fun cleanResponseContent(content: String): String {
        var text = content.trim()
        text = text.replace(Regex("""<think>[\s\S]*?</think>"""), "")
        text = text.replace(Regex("""<thought>[\s\S]*?</thought>"""), "")
        if (text.contains("```")) {
            val codeBlockMatch = Regex("""```(?:json|JSON)?\s*([\s\S]*?)\s*```""").find(text)
            if (codeBlockMatch != null) {
                return codeBlockMatch.groupValues[1].trim()
            }
        }
        return text.trim()
    }

    private fun unescapeJsonString(str: String): String {
        val sb = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (c == '\\' && i + 1 < str.length) {
                when (str[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun systemPrompt(context: Context): JSONObject {
        val language = targetLanguageName(context)
        return JSONObject()
            .put("role", "system")
            .put(
                "content",
                "You are a screen translator. Translate each numbered text item into " + language +
                    ".\n" +
                    "Rules:\n" +
                    "- Preserve numbers, URLs, code identifiers, and proper nouns as-is.\n" +
                    "- Keep translations concise so they fit near the original text location on screen.\n" +
                    "- Translate all UI text including labels, buttons, and menu items.\n" +
                    "- Do not add explanations or notes.\n" +
                    "- Respond with a single JSON array where each element is " +
                    "{\"id\":<item id>,\"text\":\"<translation>\"}.\n" +
                    "- If a text item contains no translatable content, return it unchanged.",
            )
    }

    private fun targetLanguageName(context: Context): String =
        java.util.Locale.getDefault().getDisplayName(java.util.Locale.ENGLISH)

    private fun fingerprint(text: String): String {
        val normalized = text.filter { !it.isWhitespace() }
        return java.util.Objects.hash(normalized).toString(16)
    }

    // ------------------------------------------------------------------
    // 渲染与状态分发
    // ------------------------------------------------------------------

    private fun renderOnMain(blocks: List<ScreenTranslationBlock>) {
        val main = mainHandler ?: return
        val service = overlayService
        if (!renderPending.compareAndSet(false, true)) return
        main.post {
            try {
                renderPending.set(false)
                service?.renderBlocks(blocks)
            } catch (throwable: Throwable) {
                renderPending.set(false)
                AndroidAgentLogger.warnThrottled("screen_translation_render_failed") {
                    "Screen translation render failed: ${throwable.javaClass.simpleName}"
                }
            }
        }
    }

    private fun clearOverlay() {
        val main = mainHandler ?: return
        main.post {
            overlayService?.clearBlocks()
        }
    }

    private fun updateOverlayStatus(text: String) {
        val main = mainHandler ?: return
        main.post { overlayService?.updateStatusText(text) }
    }

    private fun postOverlayError(displayMessage: String) {
        isTranslating.set(false)
        AndroidAgentLogger.warnThrottled("screen_translation_error") {
            "Screen translation error: $displayMessage"
        }
        updateOverlayStatus(displayMessage)
        AgentExecutionService.updateExecutionState(
            AgentExecutionState(
                phase = AgentExecutionPhase.IDLE,
                title = "⚠️ 翻译异常",
                detail = displayMessage,
                showChronometer = false,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 覆盖层服务引用
    // ------------------------------------------------------------------

    @Volatile
    private var overlayService: ScreenTranslationOverlayService? = null

    fun attachOverlay(service: ScreenTranslationOverlayService) {
        overlayService = service
    }

    fun detachOverlay(service: ScreenTranslationOverlayService) {
        if (overlayService === service) overlayService = null
    }

    private const val SELF_PACKAGE = "io.github.mangi.eta"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
}