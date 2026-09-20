package io.github.mangi.eta.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService

/**
 * 透明的语音权限跳板。
 *
 * 浮窗（TYPE_APPLICATION_OVERLAY + Service）没有 LocalActivityResultRegistryOwner，
 * 直接 rememberLauncherForActivityResult 申请 RECORD_AUDIO 会崩。这里借一个真实 Activity
 * 承载权限请求，结果通过静态回调回传，并暂停浮窗避免权限弹窗被遮挡。
 */
class VoicePermissionTrampolineActivity : ComponentActivity() {

    companion object {
        private var pendingCallback: ((Boolean) -> Unit)? = null

        /** 已有 RECORD_AUDIO 权限时无需跳板。 */
        fun hasRecordAudio(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /** 借跳板申请权限；结果经静态回调回传。 */
        fun requestRecordAudio(context: Context, onResult: (Boolean) -> Unit) {
            pendingCallback = onResult
            EtaAssistantOverlayService.pauseForAttachmentPicker()
            val intent = Intent(context, VoicePermissionTrampolineActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (t: Throwable) {
                pendingCallback = null
                EtaAssistantOverlayService.resumeFromAttachmentPicker()
                onResult(false)
            }
        }
    }

    private val launcher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = pendingCallback
            pendingCallback = null
            cb?.invoke(granted)
            EtaAssistantOverlayService.resumeFromAttachmentPicker()
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        if (isFinishing.not()) {
            val cb = pendingCallback
            pendingCallback = null
            cb?.invoke(false)
            EtaAssistantOverlayService.resumeFromAttachmentPicker()
        }
        super.onDestroy()
    }
}
