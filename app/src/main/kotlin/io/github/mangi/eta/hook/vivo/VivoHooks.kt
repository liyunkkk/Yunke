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
                // Vendor onCreate can block the main thread, so takeover cannot wait for it.
                // Pre-init is intentionally not in finally: finally would not run while proceed blocks.
                bootstrapCopilotOnCreate(
                    thisObject = chain.thisObject,
                    resolveContext = { value -> (value as? Context)?.applicationContext },
                    identity = ::supported,
                    tryClaim = { installed.compareAndSet(false, true) },
                    installHooks = { context ->
                        val api = NativeApi(classLoader)
                        val state = Runtime(api, VivoTextBridgeClient(context))
                        runtime = state
                        val hooks = registerBusiness(module, rootLogger, api, state)
                        business = hooks
                        val ready = hooks.report.installedCount == 7 && hooks.report.failedCount == 0 &&
                            hooks.report.missingCount == 0 && hooks.report.skippedCount == 0
                        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                            if (key == Prefs.Keys.VIVO_TEXT_BRIDGE && !enabled()) state.cancelAll()
                        }
                        preferenceListener = listener
                        Prefs.registerRemoteListener(listener)
                        rootLogger.scoped("VivoText").info(hooks.report.summary())
                        state.ready = ready
                        ready
                    },
                    onRegistrationFailure = {
                        rootLogger.scoped("VivoText").warn("本机文本接管初始化失败，未启用接管")
                    },
                    proceed = { chain.proceed() },
                )
            }
        }

    /**
     * Arm text takeover before the vendor onCreate. That call stays exactly once, after pre-init,
     * with its return value and exception unchanged. Do not move this work into finally.
     */
    internal fun <T> bootstrapCopilotOnCreate(
        thisObject: Any?,
        resolveContext: (Any?) -> Context?,
        identity: (Context) -> Boolean?,
        tryClaim: () -> Boolean,
        installHooks: (Context) -> Boolean,
        onRegistrationFailure: () -> Unit,
        proceed: () -> T,
    ): T {
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_ENTERED)
        runCatching {
            armBeforeOriginal(thisObject, resolveContext, identity, tryClaim, installHooks, onRegistrationFailure)
        }.onFailure {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED,
                VivoBridgeDiagnostics.failureCategory(it))
        }
        return callOriginalOnCreate(proceed)
    }

    private fun armBeforeOriginal(
        thisObject: Any?,
        resolveContext: (Any?) -> Context?,
        identity: (Context) -> Boolean?,
        tryClaim: () -> Boolean,
        installHooks: (Context) -> Boolean,
        onRegistrationFailure: () -> Unit,
    ) {
        val context = try {
            resolveContext(thisObject)
        } catch (error: Throwable) {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_CONTEXT_UNAVAILABLE)
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED,
                VivoBridgeDiagnostics.failureCategory(error))
            return
        }
        if (context == null) {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_CONTEXT_UNAVAILABLE)
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED)
            return
        }
        val allowed = try {
            identity(context)
        } catch (error: Throwable) {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED,
                VivoBridgeDiagnostics.failureCategory(error))
            return
        }
        when (allowed) {
            null -> {
                // supported() already recorded BOOTSTRAP_IDENTITY_QUERY_FAILED.
                VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED)
                return
            }
            false -> {
                VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_IDENTITY_REJECTED)
                VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED)
                return
            }
            true -> Unit
        }
        val claimed = try {
            tryClaim()
        } catch (error: Throwable) {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED,
                VivoBridgeDiagnostics.failureCategory(error))
            return
        }
        if (!claimed) {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_ALREADY_CLAIMED)
            return
        }
        // Do not reset CAS: failed registration can leave some hooks installed.
        // Routing remains disabled until installHooks returns true and the caller sets ready.
        val ready = try {
            installHooks(context)
        } catch (error: Throwable) {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_INIT_FAILED,
                VivoBridgeDiagnostics.failureCategory(error))
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED,
                VivoBridgeDiagnostics.failureCategory(error))
            runCatching { onRegistrationFailure() }
            return
        }
        if (ready) {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_SUCCEEDED)
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.HOOK_READY)
        } else {
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_INIT_FAILED,
                VivoBridgeDiagnostics.Failure.REGISTRATION)
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_PREINIT_FAILED,
                VivoBridgeDiagnostics.Failure.REGISTRATION)
        }
    }

    private fun <T> callOriginalOnCreate(proceed: () -> T): T {
        try {
            val result = proceed()
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_ORIGINAL_RETURNED)
            return result
        } catch (error: Throwable) {
            // Fixed stage only; the vendor exception continues unchanged and must not be wrapped.
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_ORIGINAL_THREW,
                VivoBridgeDiagnostics.failureCategory(error))
            throw error
        }
    }

    private fun enabled() = runCatching { Prefs.isEnabled(Prefs.Keys.VIVO_TEXT_BRIDGE) }.getOrDefault(false)

    // null means a query failure, false means the exact policy rejected the queried identity.
    private fun supported(context: Context): Boolean? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(PACKAGE,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signers = info.signingInfo?.apkContentsSigners?.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        }.orEmpty()
        VivoTextBridgePolicy.callerAllowed(android.os.Process.myUid(),
            pm.getPackagesForUid(android.os.Process.myUid())?.toList().orEmpty(), info.longVersionCode, signers)
    }.onFailure {
        VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.BOOTSTRAP_IDENTITY_QUERY_FAILED,
            VivoBridgeDiagnostics.failureCategory(it))
    }.getOrNull()

    private fun registerBusiness(module: XposedModule, logger: ModuleLogger, api: NativeApi, state: Runtime) =
        HookRegistrar(module, logger, "VivoText").install {
            intercept("vivo.query-start", api.queryStart, "GatewayManager.g typed query lifecycle") { chain ->
                if (state.ready && enabled()) runCatching {
                    api.queryIds(chain.args.getOrNull(0))?.let { (link, dialog) ->
                        state.begin(link, dialog)
                        api.queryCandidate(chain.args.getOrNull(0))?.let { state.captureForLink(link, it) }
                    }
                }
                chain.proceed()
            }
            intercept("vivo.typed-query", api.mapper, "LinkParamsMapper.a(RemoteQueryRequest)") { chain ->
                val mapped = chain.proceed()
                if (mapped != null && state.ready && enabled()) {
                    runCatching { api.candidate(chain.args[1]!!)?.let { state.capture(mapped, it) } }
                }
                mapped
            }
            intercept("vivo.outbound", api.send, "LinkServer.m(ChatPayload,linkId,callback)") { chain ->
                val payload = chain.args.getOrNull(0)
                val link = chain.args.getOrNull(1) as? String
                // Validate the unclaimed native dispatch before taking ownership.
                val candidate = if (link != null && VivoNativePolicy.validId(link)) state.take(link, payload) else null
                if (candidate == null || candidate.turn?.link != link) chain.proceed() else {
                    VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.DISPATCH_CLAIMED)
                    // Never proceed/fall back to vendor after this point, even for duplicates.
                    runCatching { state.claim(link!!, candidate) }.onFailure { state.failClaim(link!!, candidate) }
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
                        val dialog = intent.javaClass.getMethod("getDialogId").invoke(intent) as String
                        state.cancel(link, dialog, render = false)
                    }
                }
                chain.proceed()
            }
        }

    private data class Candidate(val dialog: String, val conversation: String, val prompt: String, val turn: VivoTurnLedger.Turn? = null)
    private data class Owned(val link: String, val candidate: Candidate, val id: String, val blockId: String)

    private class Runtime(val api: NativeApi, val client: VivoTextBridgeClient) {
        @Volatile var ready = false
        private val receipts = WeakIdentityReceipts<Candidate>()
        private val pending = ConcurrentHashMap<String, Candidate>()
        private val active = ConcurrentHashMap<String, Owned>()
        private val turns = VivoTurnLedger()
        @Synchronized fun begin(link: String, dialog: String) { turns.begin(link, dialog) }
        @Synchronized fun captureForLink(link: String, candidate: Candidate) {
            val turn = turns.find(candidate.dialog) ?: return
            pending[link] = candidate.copy(turn = turn)
        }
        @Synchronized fun capture(payload: Any, candidate: Candidate) {
            val turn = turns.find(candidate.dialog) ?: return
            val owned = candidate.copy(turn = turn)
            receipts.put(payload, owned)
            pending.remove(turn.link)
        }
        @Synchronized fun take(link: String, payload: Any?): Candidate? {
            if (payload != null) receipts.get(payload)?.let { return it }
            return pending.remove(link)
        }
        @Synchronized private fun stillOwns(owned: Owned) =
            active[owned.link] === owned && owned.candidate.turn?.let(turns::active) == true
        @Synchronized private fun finishOwned(owned: Owned): Boolean =
            active.remove(owned.link, owned).also { if (it) pending.remove(owned.link) } &&
                owned.candidate.turn?.let(turns::finish) == true
        @Synchronized fun failClaim(link: String, candidate: Candidate) {
            val turn = candidate.turn ?: return
            turns.finish(turn)
            main.post { api.safeAnswer(link, candidate, "代鱼未能接管本轮请求，未发送给厂商模型。", UUID.randomUUID().toString()) }
        }
        @Synchronized fun claim(link: String, candidate: Candidate) {
            val turn = candidate.turn ?: return
            val owned = Owned(link, candidate, "vivo_" + UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString())
            when (turns.claim(turn)) {
                VivoTurnLedger.Claim.ACTIVE_DUPLICATE -> return // coalesce with the one owned request
                VivoTurnLedger.Claim.FINISHED_DUPLICATE -> {
                    main.post { api.safeAnswer(link, candidate, "本轮请求已经处理，为避免重复调用模型，请发送新一轮提问。", owned.blockId) }
                    return
                }
                VivoTurnLedger.Claim.FULL -> {
                    main.post { api.safeAnswer(link, candidate, "本次进程的文本接管额度已用完，请重新打开小 V。", owned.blockId) }
                    return
                }
                VivoTurnLedger.Claim.CANCELLED -> {
                    if (turns.consumeCancelRender(turn)) main.post { api.safeInterrupt(owned) }
                    return
                }
                VivoTurnLedger.Claim.START -> Unit
            }
            // Publish ownership before posting; turn cancellation also covers the tiny gap above.
            active.put(link, owned)?.let { previous ->
                previous.candidate.turn?.let { turns.cancel(link, it.dialog, render = false) }
                client.cancel(previous.id)
            }
            main.post {
                if (!stillOwns(owned)) {
                    active.remove(link, owned)
                    if (turns.consumeCancelRender(turn)) api.safeInterrupt(owned)
                    return@post
                }
                if (!enabled()) {
                    if (finishOwned(owned)) api.safeAnswer(link, candidate, "代鱼文本接管已关闭。", owned.blockId)
                    return@post
                }
                client.submit(owned.id, candidate.prompt, stillOwner = { stillOwns(owned) && enabled() }) { code, text ->
                    // VivoTextBridgeClient invokes callbacks only from its main Handler.
                    if (!finishOwned(owned)) return@submit
                    if (code == "CANCELLED" || code == "CLOSED") api.safeInterrupt(owned)
                    else api.safeAnswer(link, candidate, if (code == "OK" && text != null) text else errorText(code), owned.blockId)
                }
            }
        }
        @Synchronized fun cancel(link: String, dialog: String?, render: Boolean): Boolean {
            pending.remove(link)
            turns.cancel(link, dialog, render) // also fences a query which has not reached the mapper
            val owned = active[link] ?: return false
            if (dialog != null && dialog != owned.candidate.dialog) return false
            if (!active.remove(link, owned)) return false
            VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.OWNER_CANCELLED)
            client.cancel(owned.id)
            if (render && owned.candidate.turn?.let(turns::consumeCancelRender) == true) main.post { api.safeInterrupt(owned) }
            return true
        }
        @Synchronized fun cancelAll() {
            turns.cancelAll()
            active.keys.toList().forEach { cancel(it, null, render = true) }
        }
        private fun errorText(code: String) = when (code) {
            "NO_MODEL" -> "代鱼未配置可用的纯文本模型，或该模型带有不支持的自定义请求体。"
            "TIMEOUT" -> "代鱼模型响应超时，本次请求已停止；没有切回厂商模型。"
            "BUSY", "BUSY_OR_REPLAY" -> "代鱼仍在处理或取消上一条请求，请稍后重新提问。"
            "DISABLED" -> "代鱼文本服务尚未获得本次进程的确认，请在代鱼设置中重新开启小 V 实验开关。"
            "UNTRUSTED_PEER", "UNAVAILABLE", "DISCONNECTED" -> "无法连接代鱼文本服务，请检查代鱼版本及独立开关。"
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
        // External App ClassLoader ABI: keep this literal name via the precise R8 -keepnames rule.
        private val function2 = Class.forName("kotlin.jvm.functions.Function2", false, loader)
        private val remoteQuery = clazz("gateway.model.Query\$Remote")
        private val remoteLink = method(remoteQuery, "getLinkId")
        private val remoteDialog = method(remoteQuery, "getDialogId")
        private val remoteConversation = method(remoteQuery, "getConversationId")
        private val remoteModel = method(remoteQuery, "getTextQueryModel")
        // ContinuationImpl has the same external ABI constraint; do not substitute Eta's class literal.
        val queryStart = method(gateway, "g", clazz("gateway.model.Query"),
            Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl", false, loader)).also {
            check(!Modifier.isStatic(it.modifiers) && it.returnType == Any::class.java && !it.isBridge)
        }
        fun queryIds(query: Any?): Pair<String, String>? {
            if (query == null || !remoteQuery.isInstance(query)) return null
            val link = remoteLink.invoke(query) as String
            val dialog = remoteDialog.invoke(query) as String
            return if (VivoNativePolicy.validId(link) && VivoNativePolicy.validId(dialog)) link to dialog else null
        }
        fun queryCandidate(query: Any?): Candidate? {
            if (query == null || !remoteQuery.isInstance(query)) return null
            val dialog = remoteDialog.invoke(query) as String
            val conversation = remoteConversation.invoke(query) as String
            val modelValue = remoteModel.invoke(query) ?: return null
            return candidateFrom(modelValue, dialog, conversation)
        }
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
            val modelValue = requestGetters.getValue("getModel").invoke(value) ?: return null
            val dialog = requestGetters.getValue("getDialogId").invoke(value) as String
            val conversation = requestGetters.getValue("getConversationId").invoke(value) as String
            return candidateFrom(modelValue, dialog, conversation)
        }
        private fun candidateFrom(modelValue: Any, dialog: String, conversation: String): Candidate? {
            val m = modelValue
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
            if (!VivoNativePolicy.validId(dialog) || !VivoNativePolicy.validId(conversation)) return null
            return Candidate(dialog, conversation, prompt)
        }
        private fun hide(link: String, candidate: Candidate) {
            val target = emitterField.get(executorField.get(null))
            emitSignal.invoke(target, link, hideStop.invoke(null, "hide_stop_button", candidate.conversation))
        }
        fun safeAnswer(link: String, candidate: Candidate, text: String, block: String) {
            runCatching { hide(link, candidate) }
                .onFailure { VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.NATIVE_SINK_FAILED) }
            runCatching {
                emitIntent.invoke(null, link, answer.newInstance(candidate.dialog, text, block, "", true, false))
                VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.NATIVE_REPLY_ENQUEUED)
            }.onFailure { VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.NATIVE_SINK_FAILED) }
        }
        fun safeInterrupt(owned: Owned) {
            runCatching { hide(owned.link, owned.candidate) }
                .onFailure { VivoBridgeDiagnostics.record(VivoBridgeDiagnostics.Stage.NATIVE_SINK_FAILED) }
            runCatching {
                emitIntent.invoke(null, owned.link, interrupt.newInstance(owned.candidate.dialog, "", null, null))
            }
        }
    }
}
