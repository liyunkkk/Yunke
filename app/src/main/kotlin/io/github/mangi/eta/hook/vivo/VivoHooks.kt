package io.github.mangi.eta.hook.vivo

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.agent.vivo.VivoBridgeDiagnostics
import io.github.mangi.eta.agent.vivo.VivoTextBridgeClient
import io.github.mangi.eta.agent.vivo.VivoTextBridgePolicy
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The 6090021 typed dispatch seam, not the sampled codec or an AgentRuntime entry. */
internal object VivoHooks {
    const val PACKAGE = "com.vivo.ai.copilot"
    private val installed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var runtime: Runtime? = null
    private var business: HookInstallation? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(module: XposedModule, rootLogger: ModuleLogger, classLoader: ClassLoader): HookInstallation =
        HookRegistrar(module, rootLogger, "VivoText").install bootstrap@ {
            if (Build.MODEL != "V2419A" || Build.VERSION.SDK_INT != 35) {
                skipped("vivo.device", "V2419A", "非目标机型或系统"); return@bootstrap
            }
            val application = HookSupport.findClassOrNull(classLoader, "$PACKAGE.CopilotApp")
            val onCreate = application?.let { HookSupport.findMethod(it, "onCreate") }
            if (onCreate == null) {
                missing("vivo.bootstrap", "CopilotApp.onCreate", "入口 ABI 不匹配"); return@bootstrap
            }
            intercept("vivo.bootstrap", onCreate, "CopilotApp.onCreate") { chain ->
                val result = chain.proceed()
                val context = (chain.thisObject as? Context)?.applicationContext
                if (context != null && supported(context) && installed.compareAndSet(false, true)) {
                    // Bootstrap failures do not alter Application.onCreate or install partial routing.
                    runCatching {
                        val api = NativeApi(classLoader)
                        val state = Runtime(api, VivoTextBridgeClient(context))
                        runtime = state
                        business = registerBusiness(module, rootLogger, api, state)
                        val ready = business!!.report.failedCount == 0 && business!!.report.missingCount == 0
                        state.ready = ready
                        if (ready) VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.HOOK_READY)
                        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                            if (key == Prefs.Keys.VIVO_TEXT_BRIDGE && !enabled()) state.cancelAll()
                        }
                        preferenceListener = listener
                        Prefs.registerRemoteListener(listener)
                        rootLogger.scoped("VivoText").info(business!!.report.summary())
                    }.onFailure { rootLogger.scoped("VivoText").warn("本机文本接管初始化失败，未启用接管") }
                }
                result
            }
        }

    private fun enabled() = runCatching { Prefs.isEnabled(Prefs.Keys.VIVO_TEXT_BRIDGE) }.getOrDefault(false)

    private fun supported(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(PACKAGE,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signers = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }.orEmpty()
        VivoTextBridgePolicy.callerAllowed(android.os.Process.myUid(),
            pm.getPackagesForUid(android.os.Process.myUid())?.toList().orEmpty(), info.longVersionCode, signers)
    }.getOrDefault(false)

    private fun registerBusiness(module: XposedModule, logger: ModuleLogger, api: NativeApi, state: Runtime) =
        HookRegistrar(module, logger, "VivoText").install {
            intercept("vivo.typed-query", api.mapper, "LinkParamsMapper.a(RemoteQueryRequest)") { chain ->
                val mapped = chain.proceed()
                if (mapped != null && state.ready && enabled() && chain.args.getOrNull(0) == "remote query") {
                    runCatching { api.candidate(chain.args[1]!!)?.let { state.capture(mapped, it) } }
                }
                mapped
            }
            intercept("vivo.outbound", api.send, "LinkServer.m(ChatPayload,linkId,callback)") { chain ->
                val payload = chain.args.getOrNull(0)
                val candidate = payload?.let(state::take)
                if (candidate == null) chain.proceed() else {
                    VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.DISPATCH_CLAIMED)
                    // Ownership begins here. Never proceed/fall back to vendor after claiming.
                    runCatching {
                        val link = chain.args.getOrNull(1) as? String
                        if (link != null && VivoNativePolicy.validId(link)) state.claim(link, candidate)
                    }
                    null
                }
            }
            for ((name, linkIndex, dialogIndex) in listOf(Triple("n", 2, -1), Triple("o", 2, 3))) {
                intercept("vivo.cancel-$name", api.stopMethods.getValue(name), "LinkServer.$name native stop") { chain ->
                    val link = chain.args.getOrNull(linkIndex) as? String
                    val dialog = if (dialogIndex >= 0) chain.args.getOrNull(dialogIndex) as? String else null
                    val claimed = link != null && state.cancel(link, dialog, render = true)
                    if (claimed) null else chain.proceed()
                }
            }
            intercept("vivo.close-link", api.closeLink, "LinkServer.c link teardown") { chain ->
                (chain.args.getOrNull(0) as? String)?.let { state.cancel(it, null, render = false) }
                chain.proceed()
            }
            intercept("vivo.local-interrupt", api.emitIntent, "GatewayManager.a local lifecycle") { chain ->
                val intent = chain.args.getOrNull(1)
                if (intent != null && (api.interruptClass.isInstance(intent) || api.endClass.isInstance(intent))) {
                    val link = chain.args.getOrNull(0) as? String
                    if (link != null) runCatching {
                        val dialog = intent.javaClass.getDeclaredMethod("getDialogId").invoke(intent) as String
                        state.cancel(link, dialog, render = false)
                    }
                }
                chain.proceed()
            }
        }

    private data class Candidate(val dialog: String, val conversation: String, val prompt: String)
    private data class Owned(val link: String, val candidate: Candidate, val id: String, val blockId: String)

    private class Runtime(val api: NativeApi, val client: VivoTextBridgeClient) {
        @Volatile var ready = false
        private val pending = IdentityHashMap<Any, Candidate>()
        private val active = ConcurrentHashMap<String, Owned>()
        private val ownershipLock = Any()
        private val seen = HashSet<Pair<String, String>>() // protected by ownershipLock
        fun capture(payload: Any, candidate: Candidate) {
            synchronized(pending) {
                if (pending.size >= 32) return
                pending[payload] = candidate
            }
            main.postDelayed({ synchronized(pending) { pending.remove(payload) } }, 10_000)
        }
        fun take(payload: Any): Candidate? = synchronized(pending) { pending.remove(payload) }
        fun claim(link: String, candidate: Candidate) {
            val owned = Owned(link, candidate, "vivo_" + UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString())
            val previous = synchronized(ownershipLock) {
                val key = link to candidate.dialog
                if (key in seen) return
                if (seen.size >= 256) {
                    main.post { api.safeAnswer(link, candidate, "本次进程的文本接管额度已用完，请重新打开小 V。", owned.blockId) }
                    return
                }
                seen.add(key)
                // Reserve before posting. A stop click can now cancel even before model submission.
                active.put(link, owned)
            }
            previous?.let { client.cancel(it.id) }
            main.post {
                if (active[link] !== owned) return@post
                if (!enabled()) {
                    active.remove(link, owned)
                    api.safeAnswer(link, candidate, "代鱼文本接管已关闭。", owned.blockId)
                    return@post
                }
                client.submit(owned.id, candidate.prompt) { code, text ->
                    if (!active.remove(link, owned)) return@submit
                    if (code == "CANCELLED" || code == "CLOSED") api.safeInterrupt(owned)
                    else api.safeAnswer(link, candidate, if (code == "OK" && text != null) text else errorText(code), owned.blockId)
                }
            }
        }
        fun cancel(link: String, dialog: String?, render: Boolean): Boolean {
            val owned = active[link] ?: return false
            if (dialog != null && dialog != owned.candidate.dialog) return false
            if (!active.remove(link, owned)) return false
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.OWNER_CANCELLED)
            client.cancel(owned.id)
            if (render) main.post { api.safeInterrupt(owned) }
            return true
        }
        fun cancelAll() {
            // Pending mapped payloads remain fenced: outbound consumes them without vendor fallback.
            active.keys.toList().forEach { cancel(it, null, render = true) }
        }
        private fun errorText(code: String) = when (code) {
            "NO_MODEL" -> "代鱼未配置可用的纯文本模型，或该模型带有不支持的自定义请求体。"
            "TIMEOUT" -> "代鱼模型响应超时，本次请求已停止；没有切回厂商模型。"
            "BUSY", "BUSY_OR_REPLAY" -> "代鱼仍在处理或取消上一条请求，请稍后重新提问。"
            "UNTRUSTED_PEER", "UNAVAILABLE", "DISABLED", "DISCONNECTED" -> "无法连接代鱼文本服务，请检查代鱼版本及独立开关。"
            else -> "代鱼模型请求未成功，本次没有重试或切回厂商模型。"
        }
    }

    private class NativeApi(private val loader: ClassLoader) {
        private fun clazz(name: String) = Class.forName("$PACKAGE.$name", false, loader)
        private val string = String::class.java
        private fun method(owner: Class<*>, name: String, vararg params: Class<*>) =
            owner.getDeclaredMethod(name, *params).apply { isAccessible = true }
        private fun static(owner: Class<*>, name: String, returns: Class<*>, vararg params: Class<*>) =
            method(owner, name, *params).also { check(Modifier.isStatic(it.modifiers) && it.returnType == returns && !it.isBridge) }
        private val gateway = clazz("gateway.GatewayManager")
        private val server = clazz("framework.LinkServer")
        private val request = clazz("gateway.model.RemoteQueryRequest")
        private val model = clazz("gateway.model.TextQueryModel")
        private val payload = clazz("framework.llm.cloud.logic.chat.ChatPayload")
        private val local = clazz("gateway.model.LocalIntent")
        private val signal = clazz("gateway.model.UiSignal")
        private val emitter = clazz("gateway.BlockEmitter")
        private val executor = clazz("gateway.component.LocalIntentExecutor")
        private val report = clazz("base.tool.vcode.ReportBusinessData")
        private val function2 = Class.forName("kotlin.jvm.functions.Function2", false, loader)
        val mapper = static(clazz("gateway.util.LinkParamsMapper"), "a", payload, string, request)
        val send = static(server, "m", Void.TYPE, payload, string, function2)
        val emitIntent = static(gateway, "a", Void.TYPE, string, local)
        val closeLink = static(server, "c", Void.TYPE, string)
        val stopMethods = mapOf(
            "n" to static(server, "n", Void.TYPE, Int::class.javaPrimitiveType!!, report, string, string),
            "o" to static(server, "o", Void.TYPE, Int::class.javaPrimitiveType!!, report, string, string, string),
        )
        val interruptClass = clazz("gateway.model.LocalIntent\$Interrupt")
        val endClass = clazz("gateway.model.LocalIntent\$EndDialogAndConversation")
        private val answer = clazz("gateway.model.LocalIntent\$TextAnswer").getDeclaredConstructor(
            string, string, string, string, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
        private val interrupt = interruptClass.getDeclaredConstructor(string, string, Integer::class.java, string)
        private val hideStop = static(clazz("gateway.dialogop.Interact"), "a", signal, string, string)
        private val emitSignal = method(emitter, "g", string, signal).also { check(it.returnType == Void.TYPE) }
        private val executorField = gateway.getDeclaredField("j").apply { isAccessible = true; check(type == executor) }
        private val emitterField = executor.getDeclaredField("a").apply { isAccessible = true; check(type == emitter) }
        private val requestGetters = listOf("getModel", "getDialogId", "getConversationId").associateWith { method(request, it) }
        private val modelGetters = listOf("getServerQuery", "getDisplayQuery", "getAgentId", "getInputType", "getBizSource",
            "getRenderText", "getShortcut", "getRegenerate", "getSkipRemote", "getFromRecommend", "getAttachmentQueryModel",
            "getCameraContext", "getPsAgentContext", "getTwsNotificationContext", "getExtraParams", "getScheduleContext",
            "getBotType", "getIntentions", "getNewQueryParams").associateWith { method(model, it) }
        private val intentions = clazz("framework.llm.cloud.logic.chat.common.Intentions")
        private val intentFields = listOf("first", "second", "third").map { intentions.getDeclaredField(it).apply { isAccessible = true } }
        private val extraQuery = clazz("framework.llm.cloud.logic.chat.common.NewQueryParams")
            .getDeclaredField("params").apply { isAccessible = true }

        fun candidate(value: Any): Candidate? {
            if (!request.isInstance(value)) return null
            val m = requestGetters.getValue("getModel").invoke(value) ?: return null
            fun get(name: String) = modelGetters.getValue(name).invoke(m)
            val special = listOf("getAttachmentQueryModel", "getCameraContext", "getPsAgentContext", "getTwsNotificationContext", "getExtraParams")
                .any { get(it) != null } || !((get("getScheduleContext") as? String).isNullOrBlank()) ||
                !((get("getBotType") as? String).isNullOrBlank()) ||
                get("getIntentions")?.let { obj -> intentFields.any { !(it.get(obj) as? String).isNullOrBlank() } } == true ||
                get("getNewQueryParams")?.let { extraQuery.get(it) != null } == true
            val shape = VivoNativePolicy.Shape(get("getAgentId") as? String, get("getInputType") as Int,
                get("getBizSource") as? String, get("getRenderText") as Boolean, get("getShortcut") as Boolean,
                get("getRegenerate") as Boolean, get("getSkipRemote") as Boolean, get("getFromRecommend") as Boolean, special)
            if (!VivoNativePolicy.eligible(shape)) return null
            val visible = (get("getDisplayQuery") as? String)?.takeIf { it.isNotBlank() } ?: get("getServerQuery") as? String
            val prompt = VivoNativePolicy.prompt(visible, Prefs.isEnabled(Prefs.Keys.AGENT_REQUIRE_PREFIX)) ?: return null
            val dialog = requestGetters.getValue("getDialogId").invoke(value) as String
            val conversation = requestGetters.getValue("getConversationId").invoke(value) as String
            if (!VivoNativePolicy.validId(dialog) || !VivoNativePolicy.validId(conversation)) return null
            return Candidate(dialog, conversation, prompt)
        }
        private fun hide(link: String, candidate: Candidate) {
            val target = emitterField.get(executorField.get(null))
            emitSignal.invoke(target, link, hideStop.invoke(null, "hide_stop_button", candidate.conversation))
        }
        fun safeAnswer(link: String, candidate: Candidate, text: String, block: String) {
            runCatching {
                hide(link, candidate)
                emitIntent.invoke(null, link, answer.newInstance(candidate.dialog, text, block, "", true, false))
                VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.NATIVE_REPLY_ENQUEUED)
            }.onFailure { VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.NATIVE_SINK_FAILED) }
        }
        fun safeInterrupt(owned: Owned) {
            runCatching {
                hide(owned.link, owned.candidate)
                emitIntent.invoke(null, owned.link, interrupt.newInstance(owned.candidate.dialog, "", null, null))
            }
        }
    }
}
