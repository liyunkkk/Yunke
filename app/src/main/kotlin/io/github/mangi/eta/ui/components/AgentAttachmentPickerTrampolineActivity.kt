package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService

/**
 * 透明的 Trampoline Activity，用于在悬浮窗 Service 等无法直接提供 LocalActivityResultRegistryOwner 的场景中
 * 调起系统相册、文件选择器或目录选择器，并在调起期间让出屏幕避让底层选择器，通过静态回调安全回传。
 */
class AgentAttachmentPickerTrampolineActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_PICK_IMAGES = "pick_images"
        const val ACTION_PICK_FILES = "pick_files"
        const val ACTION_PICK_FOLDER = "pick_folder"

        private var onImagesCallback: ((List<String>) -> Unit)? = null
        private var onFilesCallback: ((List<String>) -> Unit)? = null
        private var onFolderCallback: ((String) -> Unit)? = null

        @Volatile
        private var hasResumedOverlay = false

        fun pickImages(context: Context, onResult: (List<String>) -> Unit) {
            onImagesCallback = onResult
            hasResumedOverlay = false
            EtaAssistantOverlayService.pauseForAttachmentPicker()
            val intent = Intent(context, AgentAttachmentPickerTrampolineActivity::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_PICK_IMAGES)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun pickFiles(context: Context, onResult: (List<String>) -> Unit) {
            onFilesCallback = onResult
            hasResumedOverlay = false
            EtaAssistantOverlayService.pauseForAttachmentPicker()
            val intent = Intent(context, AgentAttachmentPickerTrampolineActivity::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_PICK_FILES)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun pickFolder(context: Context, onResult: (String) -> Unit) {
            onFolderCallback = onResult
            hasResumedOverlay = false
            EtaAssistantOverlayService.pauseForAttachmentPicker()
            val intent = Intent(context, AgentAttachmentPickerTrampolineActivity::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_PICK_FOLDER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        internal fun safeResumeOverlay() {
            if (!hasResumedOverlay) {
                hasResumedOverlay = true
                EtaAssistantOverlayService.resumeFromAttachmentPicker()
            }
        }
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        try {
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            if (uris.isNotEmpty()) {
                onImagesCallback?.invoke(uris.map { it.toString() })
            }
        } finally {
            onImagesCallback = null
            safeResumeOverlay()
            finish()
        }
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        try {
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            if (uris.isNotEmpty()) {
                onFilesCallback?.invoke(uris.map { it.toString() })
            }
        } finally {
            onFilesCallback = null
            safeResumeOverlay()
            finish()
        }
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        try {
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                onFolderCallback?.invoke(uri.toString())
            }
        } finally {
            onFolderCallback = null
            safeResumeOverlay()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            safeResumeOverlay()
            finish()
            return
        }
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_PICK_IMAGES -> {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            ACTION_PICK_FILES -> {
                filePicker.launch(arrayOf("*/*"))
            }
            ACTION_PICK_FOLDER -> {
                folderPicker.launch(null)
            }
            else -> {
                safeResumeOverlay()
                finish()
            }
        }
    }

    override fun onDestroy() {
        safeResumeOverlay()
        super.onDestroy()
    }
}
