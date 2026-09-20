package io.github.mangi.eta.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import io.github.mangi.eta.agent.voice.offline.SpeechInputPolicy
import io.github.mangi.eta.agent.voice.offline.SpeechDraft
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Existing generation animation doubles as opt-in dictation; never auto-starts the microphone. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatSpeechIndicator(
    textFieldState: TextFieldState,
    showGeneration: Boolean,
    interactionBlocked: Boolean,
    resetKey: Any?,
    onLongClick: (() -> Unit)? = null,
    onUnavailableClick: (() -> Unit)? = null,
    forceVisible: Boolean = false,
    suspendCapture: Boolean = false,
    startRequest: Int = 0,
    onIdleClick: (() -> Unit)? = null,
    idleDescription: String? = null,
    onStartRequested: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val pack by OfflineSpeechPack.state.collectAsState()
    val cloudConfig by io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.collectAsState()
    val speechEnabled = cloudConfig.inputEnabled
    var job by remember { mutableStateOf<Job?>(null) }
    var active by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<SpeechDraft?>(null) }
    val allowed by rememberUpdatedState(speechEnabled && (if (cloudConfig.cloudAsr) cloudConfig.asrKey.isNotBlank() else pack.ready) && !interactionBlocked && !suspendCapture)

    fun stop() {
        pendingPermission = false
        active = false
        listening = false
        draft = null
        job?.cancel()
    }
    fun start() {
        if (!allowed || job?.isCompleted == false || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val target = SpeechDraft(textFieldState.text.toString(), textFieldState.selection.start, textFieldState.selection.end)
        draft = target
        active = true
        listening = false
        job = scope.launch {
            var speechToken: Long? = null
            try {
                speechToken = io.github.mangi.eta.agent.voice.tts.SpeechPlayback.beginInput()
                val heard = io.github.mangi.eta.agent.voice.SpeechInputSession.recognize(context.applicationContext,
                    onListening = { listening = true },
                    onText = { text ->
                        val next = target.accept(textFieldState.text.toString(), text)
                            ?: throw CancellationException("Draft changed")
                        textFieldState.edit {
                            replace(0, length, next)
                            selection = TextRange(target.cursor)
                        }
                    },
                )
                if (!heard) Toast.makeText(context, R.string.speech_no_voice, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(context, if (cloudConfig.cloudAsr) failure.message ?: "豆包 ASR 识别失败" else context.getString(R.string.speech_failed), Toast.LENGTH_LONG).show()
            } catch (_: LinkageError) {
                Toast.makeText(context, R.string.speech_failed, Toast.LENGTH_LONG).show()
            } finally {
                speechToken?.let { io.github.mangi.eta.agent.voice.tts.SpeechPlayback.endInput(it) }
                active = false
                listening = false
                draft = null
            }
        }
    }

    val latestStart by rememberUpdatedState({ start() })
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingPermission
        pendingPermission = false
        if (granted && requested) latestStart()
        else if (!granted && requested) Toast.makeText(context, R.string.speech_permission_denied, Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) { io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.load(context); OfflineSpeechPack.initialize(context) }
    LaunchedEffect(allowed, resetKey) {
        stop() // Mode changes, drawer, edit, send/stream transition: invalidate permission and capture.
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { current ->
            if (active && draft?.expectedText != current) stop()
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }

    fun requestStart() {
        if (!allowed) return
        onStartRequested()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else { pendingPermission = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    var handledStartRequest by remember { mutableIntStateOf(startRequest) }
    LaunchedEffect(startRequest) {
        if (startRequest != handledStartRequest) {
            handledStartRequest = startRequest
            requestStart()
        }
    }

    val diameter by animateDpAsState(if (active) 40.dp else 24.dp, spring(dampingRatio = 0.65f), label = "speechDiameter")
    val label = stringResource(if (active) R.string.speech_stop else R.string.speech_start)
    val status = stringResource(if (listening) R.string.speech_listening else R.string.speech_preparing)
    // Parent layout data (for example BoxScope.align) must reach the outer node.
    AnimatedVisibility(
        modifier = modifier,
        visible = forceVisible || SpeechInputPolicy.visible(showGeneration, speechEnabled),
    ) {
        // Constant touch target avoids moving other composer buttons when the circle grows.
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .semantics {
                    if (speechEnabled) {
                        contentDescription = if (!active && onIdleClick != null) idleDescription ?: context.getString(R.string.voice_mode_choose) else label
                        if (active) stateDescription = status
                    }
                }
                .then(if (speechEnabled || forceVisible) Modifier.combinedClickable(
                    enabled = !interactionBlocked && (allowed || active || onLongClick != null || onUnavailableClick != null || onIdleClick != null),
                    role = Role.Button,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    hapticFeedbackEnabled = false,
                    onLongClick = onLongClick?.let { callback ->
                        {
                            TouchHaptics.longPress(view)
                            callback()
                        }
                    },
                    onClick = {
                        TouchHaptics.click(view)
                        if (active || pendingPermission) stop()
                        else if (onIdleClick != null) onIdleClick()
                        else if (!allowed) onUnavailableClick?.invoke()
                        else requestStart()
                    },
                ) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            ContainedMorphLoadingIndicator(indicatorSize = diameter, animate = showGeneration || active)
        }
    }
}
