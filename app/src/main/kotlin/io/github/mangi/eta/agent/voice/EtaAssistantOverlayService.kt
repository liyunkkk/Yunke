package io.github.mangi.eta.agent.voice

import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.AgentConversationStore
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.content.ContentResolver
import android.net.Uri
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

/**
 * Eta 数字助理的用户界面窗口。
 *
 * 系统助理会话只负责承接电源键入口；这里固定使用全屏 TYPE_APPLICATION_OVERLAY，
 * 让输入法、动画和厂商助手式浮窗拥有同一个窗口生命周期。
 */
internal class EtaAssistantOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EtaAssistantRuntimeCancel")
    }
    private val runtimeClient = AgentRuntimeClient(this, AndroidAgentLogger)
    private val runMessageProjector = AgentRunMessageProjector()
    private var conversationHistory = emptyList<AgentModelClient.ConversationMessage>()
    private var currentConversationId: String? = null
    // Q2-A：会话键必须跟随当前会话 ID，不能每次随机，
    // 否则浮窗每轮都生成新的 assistant-xxx 会话，与主界面无法统一。
    private val conversationKey: String
        get() = "eta_assistant_${currentConversationId ?: "transient"}"

    private var windowManager: WindowManager? = null
    private var windowView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var detachingWindowView: View? = null
    private val windowDetachCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var backInvokedDispatcher: OnBackInvokedDispatcher? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var runJob: Job? = null
    private var entryCaptureJob: Job? = null
    private var activeRunId: String? = null
    private var entryGeneration = 0L
    private var presentedEntryGeneration = -1L
    private var screenContextAttachment: EtaScreenContextAttachment? = null
    private var hiddenForForegroundOperation = false
    private var isPausedForPicker = false
    private var handoffInProgress = false
    private var handoffExitRequested by mutableStateOf(false)
    private var inputText by mutableStateOf("")
    private var inputFocusRequestKey by mutableIntStateOf(-1)
    private var uiState by mutableStateOf(EtaVoiceUiState())

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        activeService = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showEntry()
            ACTION_HANDOFF_READY -> finishHandoff()
            else -> showEntry()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        entryGeneration++
        entryCaptureJob?.cancel()
        entryCaptureJob = null
        screenContextAttachment = null
        cancelCurrentRun()
        removeWindow()
        scope.cancel()
        cancellationExecutor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun showEntry() {
        observeRuntimeSelection()
        if (!Settings.canDrawOverlays(this)) {
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_permission_missing") {
                "Eta assistant overlay permission is missing"
            }
            stopSelf()
            return
        }
        if (activeRunId != null) {
            // 任务仍在后台执行：重新唤起浮窗只把窗口加回来，不取消任务、不重置会话状态，
            // 也不走 showKeyboard，避免把运行中的界面状态覆盖成可输入。
            entryCaptureJob?.cancel()
            entryCaptureJob = null
            entryGeneration++
            if (windowView == null) {
                hiddenForForegroundOperation = false
                showWindow()
            }
            return
        }
        cancelCurrentRun()
        entryCaptureJob?.cancel()
        removeWindow()
        val generation = ++entryGeneration
        presentedEntryGeneration = -1L
        screenContextAttachment = null
        inputText = ""
        uiState = EtaVoiceUiState(
            messages = uiState.messages,
            conversationId = currentConversationId,
            conversationTitle = uiState.conversationTitle,
            historyConversations = uiState.historyConversations,
            isHistoryMenuVisible = false,
            screenContext = EtaScreenContextUiState(
                phase = EtaScreenContextPhase.CAPTURING,
            ),
            modelPickerState = uiState.modelPickerState,
            reasoningEffort = uiState.reasoningEffort,
            availableReasoningEfforts = uiState.availableReasoningEfforts,
        )
        scope.launch {
            loadInitialConversation()
        }
        hiddenForForegroundOperation = false
        handoffInProgress = false
        handoffExitRequested = false
        val accessibility = AgentAccessibilityService.current()
        if (accessibility == null) {
            uiState = uiState.copy(
                screenContext = EtaScreenContextUiState(
                    phase = EtaScreenContextPhase.UNAVAILABLE,
                ),
            )
            presentEntry(generation)
            return
        }
        entryCaptureJob = scope.launch {
            val result = accessibility.captureScreenshotExcludingOverlays(
                onWindowsSubmitted = {
                    scope.launch(Dispatchers.Main.immediate) {
                        presentEntry(generation)
                    }
                },
            )
            val bitmap = result.bitmap
            val attachment = if (bitmap == null || result.criticalWindowMissing) {
                null
            } else {
                try {
                    runCatching {
                        val image = AgentImageCodec.fromScreenContextBitmap(
                            bitmap,
                            source = "screen_context",
                        )
                        val preview = AgentImageCodec.previewFromReference(
                            this@EtaAssistantOverlayService,
                            image,
                        ) ?: return@runCatching null
                        EtaScreenContextAttachment(
                            image = image,
                            previewDataUrl = preview.reference,
                        )
                    }.onFailure { throwable ->
                        AndroidAgentLogger.warn(
                            "Eta assistant entry screenshot encode failed: " +
                                "type=${throwable.javaClass.simpleName}"
                        )
                    }.getOrNull()
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != entryGeneration) return@withContext
                entryCaptureJob = null
                screenContextAttachment = attachment
                uiState = uiState.copy(
                    screenContext = if (attachment == null) {
                        EtaScreenContextUiState(phase = EtaScreenContextPhase.UNAVAILABLE)
                    } else {
                        EtaScreenContextUiState(
                            phase = EtaScreenContextPhase.AVAILABLE,
                            previewDataUrl = attachment.previewDataUrl,
                        )
                    },
                )
                presentEntry(generation)
            }
        }
    }

    private fun presentEntry(generation: Long) {
        if (generation != entryGeneration || presentedEntryGeneration == generation) return
        presentedEntryGeneration = generation
        showWindow()
        if (windowView == null) {
            stopSelf()
            return
        }
        showKeyboard()
    }

    private fun showWindow() {
        if (windowView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = createComposeView {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = AppearanceSettings())
            AgentAppTheme(
                appearance = appearance,
                applyInterfaceScale = false,
            ) {
                // ColorOS 在 Overlay 窗口切换期间可能短暂使用软件画布；RuntimeShader
                // 无法在该画布绘制，因此浮窗统一使用 Miuix 的圆角回退路径。
                CompositionLocalProvider(LocalSquircleEnabled provides false) {
                    val activeAssistantId by AssistantRepository.activeId.collectAsState()
                    EtaVoicePanel(
                        state = uiState,
                        input = inputText,
                        inputFocusRequestKey = inputFocusRequestKey,
                        onScreenContextSelect = ::selectScreenContext,
                        onScreenContextRemove = ::removeScreenContext,
                        onScreenTranslation = ::startScreenTranslation,
                        onToggleHistoryMenu = ::toggleHistoryMenu,
                        onSelectConversation = ::selectConversation,
                        onNewConversation = ::newConversation,
                        onModelSelected = { _, modelId -> selectModel(modelId) },
                        onSubmit = { text ->
                            inputText = text
                            submitInput()
                        },
                        onStop = ::stopCurrentRun,
                        onClose = ::dismissOrContinueInBackground,
                        canOpenConversation = activeRunId == null &&
                            uiState.messages.any { message ->
                                message is AgentMessageUi && message.content.isNotBlank()
                            },
                        exitRequested = handoffExitRequested,
                        onOpenConversation = ::openConversation,
                        onAttachImage = ::attachImage,
                        onAttachVideo = ::attachVideo,
                        onRemoveImage = ::removePendingImage,
                        onAttachFiles = ::attachFiles,
                        onAttachFolder = ::attachFolder,
                        onAttachFilePath = ::attachFilePath,
                        onRemoveFileReference = ::removePendingFileReference,
                        onReasoningEffortChange = ::updateReasoningEffort,
                        onAssistantSelected = { id -> AssistantRepository.select(id) },
                        // 浮窗不承载助手编辑页，交给主界面处理。
                        onEditAssistant = {},
                        history = conversationHistory,
                        autoCompressEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED),
                        assistantId = activeAssistantId,
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            dimAmount = 0f
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            title = "EtaAssistantOverlay"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                // 柔光玻璃：对齐原版 79a23839 的模糊半径（110），
                // 24 会让浮窗背景看起来是"半透明色块"而非磨砂玻璃。
                blurBehindRadius = 110
            }
        }
        runCatching { wm.addView(view, params) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_add_failed") {
                "Eta assistant overlay addView failed: type=${throwable.javaClass.simpleName}"
            }
            return
        }
        windowManager = wm
        windowView = view
        windowParams = params
        registerSystemBackCallback(view)
        view.requestFocus()
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@EtaAssistantOverlayService)
            setViewTreeSavedStateRegistryOwner(this@EtaAssistantOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        }

    private fun registerSystemBackCallback(view: View) {
        unregisterSystemBackCallback()
        val dispatcher = view.findOnBackInvokedDispatcher()
        if (dispatcher == null) {
            AndroidAgentLogger.warn("Eta assistant overlay back dispatcher unavailable")
            return
        }
        val callback = OnBackInvokedCallback(::dismissOrContinueInBackground)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backInvokedDispatcher = dispatcher
        backInvokedCallback = callback
    }

    private fun unregisterSystemBackCallback() {
        val dispatcher = backInvokedDispatcher
        val callback = backInvokedCallback
        backInvokedDispatcher = null
        backInvokedCallback = null
        if (dispatcher != null && callback != null) {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    private fun showKeyboard(status: EtaVoiceStatus = EtaVoiceStatus.InputRequest) {
        uiState = uiState.copy(
            phase = EtaVoicePhase.READY,
            status = status,
        )
        updateSoftInput(visible = true)
        inputFocusRequestKey++
    }

    private fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = this@EtaAssistantOverlayService,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main.immediate) {
                        Toast.makeText(
                            this@EtaAssistantOverlayService,
                            getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(this@EtaAssistantOverlayService, image)
                    ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main.immediate) {
                    uiState = uiState.copy(pendingImages = uiState.pendingImages + pending)
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    private fun removePendingImage(id: String) {
        uiState = uiState.copy(pendingImages = uiState.pendingImages.filterNot { it.id == id })
    }

    private fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(this@EtaAssistantOverlayService, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    private fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(this@EtaAssistantOverlayService, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                ),
            )
        }
    }

    private fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    private fun removePendingFileReference(id: String) {
        uiState = uiState.copy(
            pendingFileReferences = uiState.pendingFileReferences.filterNot { it.id == id },
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main.immediate) {
                val existingPaths = uiState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    uiState = uiState.copy(
                        pendingFileReferences = uiState.pendingFileReferences + additions,
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(this@EtaAssistantOverlayService, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath ->
                getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound ->
                getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType ->
                getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch ->
                getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable ->
                getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied ->
                getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed ->
                getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge ->
                getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut ->
                getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    private fun submitInput() {
        val prompt = inputText.trim()
        val hasAttachment = uiState.pendingImages.isNotEmpty() ||
            uiState.pendingFileReferences.isNotEmpty()
        if ((prompt.isBlank() && !hasAttachment) || activeRunId != null) return
        submitPrompt(prompt)
    }

    private fun submitPrompt(prompt: String) {
        val normalized = prompt.trim()
        val pendingImages = uiState.pendingImages
        val pendingFileReferences = uiState.pendingFileReferences
        if (
            (normalized.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty()) ||
            activeRunId != null
        ) {
            return
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        val runtimePrompt = AgentFileReferencePromptCodec.format(normalized, fileReferences)
        val attachment = screenContextAttachment.takeIf { uiState.screenContext.selected }
        val runImages = attachment?.let { listOf(it.image) }.orEmpty() +
            pendingImages.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.dataUrl.length,
                    source = image.uri,
                )
            }
        val previewImages = attachment?.let { listOf(it.previewDataUrl) }.orEmpty() +
            pendingImages.map { it.dataUrl }
        screenContextAttachment = null
        inputText = ""
        if (currentConversationId == null) {
            currentConversationId = "conv-${UUID.randomUUID()}"
        }
        val targetConvId = currentConversationId ?: return
        val newTitle = uiState.conversationTitle.ifBlank { normalized.take(40) }
        activeRunId = UUID.randomUUID().toString()
        val runId = activeRunId ?: return
        uiState = uiState.copy(
            conversationId = targetConvId,
            conversationTitle = newTitle,
            isHistoryMenuVisible = false,
            phase = EtaVoicePhase.PROCESSING,
            status = EtaVoiceStatus.Reasoning,
            screenContext = EtaScreenContextStateReducer.consume(),
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            messages = uiState.messages + UserMessageUi(
                id = "user-$runId",
                content = runtimePrompt,
                images = previewImages,
            ),
        )
        updateSoftInput(visible = false)
        runJob = scope.launch {
            val config = AgentModelClient.loadConfig()
            val payload = AgentExternalArchivePayload(
                userText = runtimePrompt,
                conversationKey = conversationKey,
                title = normalized.ifBlank { runtimePrompt }.take(40),
            )
            val result = runtimeClient.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = runtimePrompt,
                    config = config,
                    images = runImages,
                    history = conversationHistory,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = "$conversationKey:$runId",
                        source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                        payload = payload.toJson(),
                        dismissEntrySurfaceOnForegroundOperation = true,
                    ),
                ),
                onEvent = { event -> handleRuntimeEvent(runId, event) },
            )
            val shouldStopAfterResult = withContext(Dispatchers.Main.immediate) {
                if (activeRunId != runId) return@withContext false
                activeRunId = null
                runJob = null
                if (result.ok) {
                    conversationHistory = conversationHistory +
                        AgentModelClient.buildUserHistoryMessage(runtimePrompt, runImages) +
                        result.transcript
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.READY,
                        status = EtaVoiceStatus.Completed,
                        messages = finishRunMessages(runId, result),
                    )
                } else {
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.ERROR,
                        status = EtaVoiceStatus.Failed(result.error),
                        messages = finishRunMessages(runId, result),
                    )
                }
                if (!hiddenForForegroundOperation) {
                    updateSoftInput(visible = false)
                }
                val finalMessages = uiState.messages
                val finalHistory = conversationHistory
                val saveConvId = currentConversationId ?: targetConvId
                val saveTitle = uiState.conversationTitle.ifBlank { newTitle }
                scope.launch {
                    AgentConversationStore.saveAssistantConversation(
                        context = this@EtaAssistantOverlayService,
                        conversationId = saveConvId,
                        title = saveTitle,
                        messages = finalMessages,
                        history = finalHistory,
                    )
                    refreshHistoryConversations(saveConvId)
                }
                hiddenForForegroundOperation
            }
            runtimeClient.ackResult(runId)
            if (shouldStopAfterResult) {
                withContext(Dispatchers.Main.immediate) {
                    if (activeRunId == null) {
                        removeWindow()
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun handleRuntimeEvent(runId: String, event: AgentEvent) {
        scope.launch(Dispatchers.Main.immediate) {
            if (activeRunId != runId) return@launch
            if (AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)) {
                hideForForegroundOperation()
            }
            uiState = projectRuntimeEvent(runId, event, uiState)
        }
    }

    private fun projectRuntimeEvent(
        runId: String,
        event: AgentEvent,
        state: EtaVoiceUiState,
    ): EtaVoiceUiState {
        var messages = state.messages
        var status = state.status
        var phase = state.phase
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                messages = runMessageProjector.startAssistantBlock(runId, event, messages)
            }

            is AgentEvent.AssistantBlockDelta -> {
                messages = when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT ->
                        runMessageProjector.appendTextDelta(
                            runId,
                            event.round,
                            event.index,
                            event.delta,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.THINKING ->
                        runMessageProjector.appendReasoningDelta(
                            runId,
                            event.round,
                            event.index,
                            event.delta,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                messages = when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT ->
                        runMessageProjector.finalizeTextBlock(
                            runId,
                            event.round,
                            event.index,
                            event.replacementContent,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.THINKING ->
                        runMessageProjector.finalizeThinkingBlock(
                            runId,
                            event.round,
                            event.index,
                            event.replacementContent,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
            }

            is AgentEvent.UsageReceived -> {
                if (!event.projected) {
                    val assistantPrefix = "assistant-$runId-${event.round}"
                    val usage = TokenUsageUi(
                        contextTokens = event.usage.contextTokens,
                        inputTokens = event.usage.inputTokens,
                        outputTokens = event.usage.outputTokens,
                        reasoningTokens = event.usage.reasoningTokens,
                        cachedTokens = event.usage.cachedTokens,
                    )
                    val targetIndex = messages.indexOfLast { message ->
                        message is AgentMessageUi &&
                            (message.id == assistantPrefix || message.id.startsWith("$assistantPrefix-"))
                    }
                    messages = messages.mapIndexed { index, message ->
                        if (index == targetIndex && message is AgentMessageUi) {
                            message.copy(usage = usage)
                        } else {
                            message
                        }
                    }
                }
            }

            is AgentEvent.UserSupplementReceived -> {
                val id = "user-$runId-supplement-${event.index}"
                if (messages.none { it.id == id }) {
                    messages = messages + UserMessageUi(id = id, content = event.text)
                }
            }

            is AgentEvent.ToolStarted -> {
                status = EtaVoiceStatus.RunningTool(event.name)
                messages = runMessageProjector.startTool(
                    runId,
                    event,
                    runMessageProjector.finalizeTextRound(
                        runId,
                        event.round,
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages),
                    ),
                )
            }

            is AgentEvent.ToolFinished -> {
                messages = runMessageProjector.finishTool(runId, event, messages)
            }

            is AgentEvent.HostedToolStarted -> {
                status = EtaVoiceStatus.RunningTool(event.name)
                messages = runMessageProjector.startHostedTool(
                    runId,
                    event,
                    runMessageProjector.finalizeTextRound(
                        runId,
                        event.round,
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages),
                    ),
                )
            }

            is AgentEvent.HostedToolFinished -> {
                messages = runMessageProjector.finishHostedTool(runId, event, messages)
            }

            is AgentEvent.RunFailed -> {
                phase = EtaVoicePhase.ERROR
                status = EtaVoiceStatus.Failed(event.reason)
                messages = runMessageProjector.failRunningTools(
                    event.reason,
                    runMessageProjector.finalizeText(
                        runId,
                        runMessageProjector.finalizeThinking(runId, messages),
                    ),
                )
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    messages = runMessageProjector.ensureCompletedThinking(
                        runId = runId,
                        round = event.round,
                        content = event.reasoningContent,
                        messages = messages,
                    )
                }
            }

            is AgentEvent.RunFinished -> {
                messages = runMessageProjector.finalizeText(
                    runId,
                    runMessageProjector.finalizeThinking(runId, messages),
                )
            }

            is AgentEvent.ModelRetryScheduled -> {
                messages = runMessageProjector.scheduleModelRetry(runId, event, messages)
                status = EtaVoiceStatus.Reasoning
            }
            is AgentEvent.ProviderRequestStarted -> status = EtaVoiceStatus.Reasoning
            is AgentEvent.ChildContextUpdated,
            is AgentEvent.RunStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            is AgentEvent.ContextCompactionStarted,
            is AgentEvent.ContextCompacted,
            -> Unit
        }
        return state.copy(messages = messages, phase = phase, status = status)
    }

    private fun finishRunMessages(
        runId: String,
        result: AgentRuntimeWire.RunResult,
    ): List<AgentChatMessageUi> {
        var messages = runMessageProjector.finalizeText(
            runId,
            runMessageProjector.finalizeThinking(runId, uiState.messages),
        )
        if (!result.ok) {
            messages = runMessageProjector.failRunningTools(
                result.error ?: SYNTHETIC_RUNTIME_FAILED,
                messages,
            )
        }
        val notice = when {
            result.ok && result.content.isBlank() -> SystemNoticeCode.EmptyResult
            !result.ok && result.error == LEGACY_STOPPED_ERROR -> SystemNoticeCode.Stopped
            !result.ok -> SystemNoticeCode.RuntimeFailed
            else -> null
        }
        val lastAssistantIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
        messages = if (lastAssistantIndex >= 0) {
            val targetRound = (messages[lastAssistantIndex] as AgentMessageUi).id
                .assistantRound(runId)
            val sameRoundBlocks = targetRound?.let { round ->
                messages.count { message ->
                    message is AgentMessageUi && message.id.assistantRound(runId) == round
                }
            } ?: 0
            messages.mapIndexed { index, message ->
                if (index == lastAssistantIndex && message is AgentMessageUi) {
                    if (notice == null) {
                        message.copy(
                            content = if (sameRoundBlocks <= 1) {
                                result.content
                            } else {
                                message.content.ifBlank { result.content }
                            },
                            isStreaming = false,
                            renderMarkdown = true,
                        )
                    } else {
                        SystemNoticeMessageUi(
                            id = message.id,
                            code = notice,
                            detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                        )
                    }
                } else {
                    message
                }
            }
        } else {
            if (notice == null) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = result.content,
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                messages + SystemNoticeMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    code = notice,
                    detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                )
            }
        }
        runMessageProjector.clearRun(runId)
        return messages
    }

    private fun String.assistantRound(runId: String): Int? {
        val prefix = "assistant-$runId-"
        return removePrefix(prefix)
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()
    }

    private fun stopCurrentRun() {
        val runId = activeRunId
        if (runId != null) {
            activeRunId = null
            requestRuntimeCancellation(runId)
            runJob?.cancel()
            runJob = null
            uiState = uiState.copy(
                phase = EtaVoicePhase.READY,
                status = EtaVoiceStatus.Stopped,
                messages = runMessageProjector.failRunningTools(
                    SYNTHETIC_STOPPED,
                    runMessageProjector.finalizeText(
                        runId,
                        runMessageProjector.finalizeThinking(runId, uiState.messages),
                    ),
                ),
            )
            runMessageProjector.clearRun(runId)
            updateSoftInput(visible = true)
            inputFocusRequestKey++
        } else {
            dismissAndStop()
        }
    }

    private fun cancelCurrentRun() {
        val runId = activeRunId ?: return
        activeRunId = null
        requestRuntimeCancellation(runId)
        runJob?.cancel()
        runJob = null
    }

    private fun requestRuntimeCancellation(runId: String) {
        runCatching {
            cancellationExecutor.execute { runtimeClient.cancelRun(runId) }
        }
    }

    private fun pauseWindowForPicker() {
        if (isPausedForPicker) return
        isPausedForPicker = true
        updateSoftInput(visible = false)
        removeWindow()
    }

    private fun resumeWindowFromPicker() {
        if (!isPausedForPicker) return
        isPausedForPicker = false
        if (detachingWindowView != null) {
            windowDetachCallbacks.add {
                if (!isPausedForPicker && windowView == null) {
                    showWindow()
                }
            }
        } else {
            showWindow()
        }
    }

    private fun updateSoftInput(visible: Boolean) {
        val wm = windowManager ?: return
        val view = windowView ?: return
        val params = windowParams ?: return
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
            if (visible) {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            } else {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun selectScreenContext() {
        uiState = uiState.copy(
            screenContext = EtaScreenContextStateReducer.select(
                state = uiState.screenContext,
                enabled = activeRunId == null,
                hasAttachment = screenContextAttachment != null,
            ),
        )
    }

    /**
     * 屏幕翻译入口：启动翻译控制器与覆盖层，隐藏自身面板。
     * 翻译运行期间不再需要本面板，用户通过覆盖层控制胶囊停止翻译。
     */
    private fun startScreenTranslation() {
        io.github.mangi.eta.agent.translation.ScreenTranslationController.start(applicationContext)
        io.github.mangi.eta.agent.translation.ScreenTranslationOverlayService.show(applicationContext)
        hideForForegroundOperation()
    }

    private fun removeScreenContext() {
        uiState = uiState.copy(
            screenContext = EtaScreenContextStateReducer.remove(
                state = uiState.screenContext,
                enabled = activeRunId == null,
            ),
        )
    }

    private suspend fun loadInitialConversation() {
        val convData = AgentConversationStore.loadAssistantConversation(this, currentConversationId)
        val recent = AgentConversationStore.loadRecentConversations(this)
        withContext(Dispatchers.Main.immediate) {
            if (convData != null) {
                currentConversationId = convData.conversationId
                conversationHistory = convData.history
                val items = recent.map { meta ->
                    AssistantConversationItem(
                        id = meta.id,
                        title = meta.title,
                        updatedAt = meta.updatedAt,
                        isCurrent = meta.id == convData.conversationId,
                    )
                }
                uiState = uiState.copy(
                    messages = convData.messages.distinctBy { it.id },
                    conversationId = convData.conversationId,
                    conversationTitle = convData.title,
                    historyConversations = items,
                )
            } else {
                val newId = "conv-${UUID.randomUUID()}"
                currentConversationId = newId
                val items = recent.map { meta ->
                    AssistantConversationItem(
                        id = meta.id,
                        title = meta.title,
                        updatedAt = meta.updatedAt,
                        isCurrent = false,
                    )
                }
                uiState = uiState.copy(
                    conversationId = newId,
                    historyConversations = items,
                )
            }
        }
    }

    private fun refreshHistoryConversations(activeId: String? = currentConversationId) {
        scope.launch {
            val recent = AgentConversationStore.loadRecentConversations(
                this@EtaAssistantOverlayService,
            )
            withContext(Dispatchers.Main.immediate) {
                val items = recent.map { meta ->
                    AssistantConversationItem(
                        id = meta.id,
                        title = meta.title,
                        updatedAt = meta.updatedAt,
                        isCurrent = meta.id == activeId,
                    )
                }
                uiState = uiState.copy(historyConversations = items)
            }
        }
    }

    private fun toggleHistoryMenu() {
        val next = !uiState.isHistoryMenuVisible
        uiState = uiState.copy(isHistoryMenuVisible = next)
        if (next) {
            updateSoftInput(visible = false)
            refreshHistoryConversations()
        }
    }

    private fun selectConversation(targetId: String) {
        if (activeRunId != null) return
        if (targetId == currentConversationId) {
            uiState = uiState.copy(isHistoryMenuVisible = false)
            return
        }
        scope.launch {
            val data = AgentConversationStore.loadAssistantConversation(
                this@EtaAssistantOverlayService,
                targetId,
            )
            val recent = AgentConversationStore.loadRecentConversations(
                this@EtaAssistantOverlayService,
            )
            if (data != null) {
                AgentConversationStore.selectConversation(
                    this@EtaAssistantOverlayService,
                    data.conversationId,
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (data != null) {
                    currentConversationId = data.conversationId
                    conversationHistory = data.history
                    val items = recent.map { meta ->
                        AssistantConversationItem(
                            id = meta.id,
                            title = meta.title,
                            updatedAt = meta.updatedAt,
                            isCurrent = meta.id == data.conversationId,
                        )
                    }
                    inputText = ""
                    uiState = uiState.copy(
                        messages = data.messages.distinctBy { it.id },
                        conversationId = data.conversationId,
                        conversationTitle = data.title,
                        historyConversations = items,
                        isHistoryMenuVisible = false,
                        pendingImages = emptyList(),
                        pendingFileReferences = emptyList(),
                    )
                }
            }
        }
    }

    private fun newConversation() {
        cancelCurrentRun()
        val newId = "conv-${UUID.randomUUID()}"
        currentConversationId = newId
        conversationHistory = emptyList()
        inputText = ""
        uiState = uiState.copy(
            messages = emptyList(),
            conversationId = newId,
            conversationTitle = "",
            isHistoryMenuVisible = false,
            historyConversations = uiState.historyConversations.map { it.copy(isCurrent = false) },
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
        )
        showKeyboard()
    }

    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var selectionObservationStarted = false

    private fun observeRuntimeSelection() {
        if (selectionObservationStarted) return
        selectionObservationStarted = true
        scope.launch(Dispatchers.IO) {
            combine(
                RuntimeConfigRepository.selectedProviderIdFlow(),
                RuntimeConfigRepository.selectedModelIdFlow(),
                ProviderRepository.providersFlow(),
            ) { providerId, modelId, providers ->
                Triple(providerId, modelId, providers)
            }
                .distinctUntilChanged()
                .collectLatest { (providerId, modelId, providers) ->
                    val pickerState = AgentModelPickerProjector.project(
                        providers = providers,
                        selectedProviderId = providerId,
                        selectedModelId = modelId,
                    )
                    val capabilities = RuntimeConfigRepository.currentRuntimeConfig()
                        ?.reasoningCapabilities
                    withContext(Dispatchers.Main.immediate) {
                        currentReasoningCapabilities = capabilities
                        val normalized = capabilities?.normalize(uiState.reasoningEffort) ?: ReasoningEffort.OFF
                        uiState = uiState.copy(
                            modelPickerState = pickerState.copy(
                                isChanging = uiState.modelPickerState.isChanging,
                            ),
                            reasoningEffort = normalized,
                            availableReasoningEfforts = capabilities?.selectableEfforts.orEmpty(),
                        )
                    }
                }
        }
    }

    /** 推理强度切换：按当前模型能力归一化后写回浮窗状态。 */
    private fun updateReasoningEffort(effort: ReasoningEffort) {
        val normalized = currentReasoningCapabilities?.normalize(effort) ?: ReasoningEffort.OFF
        uiState = uiState.copy(reasoningEffort = normalized)
    }
    /** 视频附件：与主界面共用 AgentVideoCodec 导入，产出 isVideo 的 PendingImageUi。 */
    private fun attachVideo(uri: String) {
        scope.launch(Dispatchers.IO) {
            val attachment = runCatching {
                AgentVideoCodec.importFromUri(this@EtaAssistantOverlayService, Uri.parse(uri))
            }.getOrNull()
            if (attachment == null) {
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        this@EtaAssistantOverlayService,
                        getString(R.string.state_ui_unable_to_read_this_video),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            val pending = PendingImageUi(
                id = "vid-${UUID.randomUUID()}",
                uri = attachment.file.absolutePath,
                dataUrl = attachment.thumbnail.reference,
                mimeType = attachment.mimeType,
                isVideo = true,
                durationMs = attachment.durationMs.takeIf { it > 0L },
                byteSize = attachment.bytes,
            )
            withContext(Dispatchers.Main.immediate) {
                uiState = uiState.copy(pendingImages = uiState.pendingImages + pending)
            }
        }
    }
    private fun selectModel(modelId: String) {
        if (activeRunId != null || uiState.modelPickerState.isChanging ||
            uiState.modelPickerState.selectedModel?.id == modelId
        ) {
            return
        }
        uiState = uiState.copy(
            modelPickerState = uiState.modelPickerState.copy(isChanging = true),
        )
        scope.launch(Dispatchers.IO) {
            try {
                RuntimeConfigRepository.setSelectedModelId(modelId)
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(
                        this@EtaAssistantOverlayService,
                        getString(R.string.state_ui_model_switching_failed_please_try_again_later_4af439),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    uiState = uiState.copy(
                        modelPickerState = uiState.modelPickerState.copy(isChanging = false),
                    )
                }
            }
        }
    }

    private fun hideForForegroundOperation(onComplete: ((Boolean) -> Unit)? = null) {
        hiddenForForegroundOperation = true
        EtaVoiceInteractionSession.requestHideForForegroundOperation(this)
        removeWindow(onComplete)
    }

    private fun removeWindow(onComplete: ((Boolean) -> Unit)? = null) {
        unregisterSystemBackCallback()
        detachingWindowView?.let { detachingView ->
            onComplete?.let(windowDetachCallbacks::add)
            if (!detachingView.isAttachedToWindow) {
                finishWindowDetach(success = true)
            }
            return
        }

        val view = windowView
        val wm = windowManager
        if (view == null || wm == null || !view.isAttachedToWindow) {
            windowView = null
            windowParams = null
            windowManager = null
            onComplete?.invoke(true)
            return
        }

        onComplete?.let(windowDetachCallbacks::add)
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                finishWindowDetach(success = true)
            }
        }
        detachingWindowView = view
        view.addOnAttachStateChangeListener(attachListener)

        val removed = runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            wm.removeView(view)
            true
        }.getOrElse { throwable ->
            view.removeOnAttachStateChangeListener(attachListener)
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_remove_failed") {
                "Eta assistant overlay removeView failed: type=${throwable.javaClass.simpleName}"
            }
            false
        }
        if (!removed) {
            finishWindowDetach(success = false)
            return
        }

        windowView = null
        windowParams = null
        windowManager = null
        if (!view.isAttachedToWindow) {
            view.removeOnAttachStateChangeListener(attachListener)
            finishWindowDetach(success = true)
        }
    }

    private fun finishWindowDetach(success: Boolean) {
        detachingWindowView = null
        val callbacks = windowDetachCallbacks.toList()
        windowDetachCallbacks.clear()
        callbacks.forEach { callback -> callback(success) }
    }

    /**
     * 关闭浮窗入口（关闭按钮 / 返回键 / 下拉手势 / 点击遮罩）。
     *
     * 任务仍在执行时只隐藏窗口，运行在后台继续，完成后由灵动岛与通知提示；
     * 没有在执行的任务时才真正关闭服务。要中止任务请用面板上的停止按钮。
     */
    private fun dismissOrContinueInBackground() {
        if (activeRunId != null) {
            hideForForegroundOperation()
            return
        }
        dismissAndStop()
    }

    private fun dismissAndStop() {
        entryGeneration++
        entryCaptureJob?.cancel()
        entryCaptureJob = null
        screenContextAttachment = null
        cancelCurrentRun()
        removeWindow()
        stopSelf()
    }

    private fun openConversation() {
        if (handoffInProgress || activeRunId != null || uiState.messages.isEmpty()) return
        handoffInProgress = true
        AndroidAgentLogger.info("Eta assistant handoff requested")
        updateSoftInput(visible = false)
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN_CONVERSATION)
            .putExtra(EXTRA_CONVERSATION_KEY, conversationKey)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        val creatorOptions = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                pendingIntentCreatorBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            HANDOFF_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions.toBundle(),
        )
        val senderOptions = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
        }
        runCatching { pendingIntent.send(senderOptions.toBundle()) }
            .onFailure {
                handoffInProgress = false
                AndroidAgentLogger.warn("Eta assistant handoff activity launch failed")
                return
            }
        scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_TIMEOUT_MS)
            if (handoffInProgress) {
                AndroidAgentLogger.warn("Eta assistant handoff timed out waiting for chat")
                handoffInProgress = false
            }
        }
    }

    private fun finishHandoff() {
        if (!handoffInProgress) return
        if (handoffExitRequested) return
        AndroidAgentLogger.info("Eta assistant handoff chat ready")
        handoffExitRequested = true
        scope.launch(Dispatchers.Main.immediate) {
            delay(HANDOFF_EXIT_DURATION_MS)
            handoffInProgress = false
            removeWindow()
            stopSelf()
        }
    }

    internal companion object {
        const val ACTION_SHOW = "io.github.mangi.eta.agent.voice.SHOW"
        const val ACTION_OPEN_CONVERSATION = "io.github.mangi.eta.agent.voice.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_KEY = "io.github.mangi.eta.agent.voice.extra.CONVERSATION_KEY"
        private const val ACTION_HANDOFF_READY = "io.github.mangi.eta.agent.voice.HANDOFF_READY"
        private const val HANDOFF_TIMEOUT_MS = 5_000L
        private const val HANDOFF_EXIT_DURATION_MS = 220L
        private const val HANDOFF_REQUEST_CODE = 0x455441
        private const val LEGACY_STOPPED_ERROR = "已停止"
        private const val SYNTHETIC_STOPPED = "eta_status:stopped"
        private const val SYNTHETIC_RUNTIME_FAILED = "eta_status:runtime_failed"
        private const val FOREGROUND_DISMISS_TIMEOUT_MS = 2_000L
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var activeService: EtaAssistantOverlayService? = null

        /**
         * Eta 自己拥有入口浮层，直接关闭并等待具体 View detach；不能按包名猜测，
         * 因为入口、Runtime 与结果浮层都属于同一个包。
         */
        fun dismissForForegroundOperation(context: Context): Boolean {
            val service = activeService
            if (service == null) {
                EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                return true
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideForForegroundOperation()
                return service.windowView == null && service.detachingWindowView == null
            }

            val completed = CountDownLatch(1)
            val dismissed = AtomicBoolean(false)
            mainHandler.post {
                val current = activeService
                if (current == null) {
                    EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                    dismissed.set(true)
                    completed.countDown()
                } else if (current !== service) {
                    completed.countDown()
                } else {
                    current.hideForForegroundOperation { success ->
                        dismissed.set(success)
                        completed.countDown()
                    }
                }
            }
            return try {
                completed.await(FOREGROUND_DISMISS_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
                    dismissed.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun show(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_SHOW),
            )
        }

        fun isServiceActive(): Boolean = activeService != null
        fun pauseForAttachmentPicker() {
            mainHandler.post {
                activeService?.pauseWindowForPicker()
            }
        }

        fun resumeFromAttachmentPicker() {
            mainHandler.post {
                activeService?.resumeWindowFromPicker()
            }
        }


        fun dismiss(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java),
            )
        }

        fun notifyHandoffReady(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_HANDOFF_READY),
            )
        }
    }
}

private data class EtaScreenContextAttachment(
    val image: AgentModelClient.ModelImage,
    val previewDataUrl: String,
)
