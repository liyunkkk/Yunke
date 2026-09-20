package io.github.mangi.eta.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal val ChatInputPopupMargin = 8.dp
internal val ChatInputActionSize = 40.dp
internal val ChatInputActionIconSize = 24.dp

/**
 * 附件选择器启动句柄。
 *
 * 输入栏「+」按钮的弹出菜单与工具箱面板共用同一套选择逻辑与回退路径，
 * 避免出现两份行为可能走样的实现。
 */
internal class AttachmentPickerLaunchers(
    val pickImage: () -> Unit,
    val pickVideo: () -> Unit,
    val pickFiles: () -> Unit,
    val pickFolder: () -> Unit,
)

/**
 * 创建附件选择器启动句柄。
 *
 * 存在 ActivityResultRegistry 时走 Compose 选择器；悬浮窗 Service 等场景没有
 * LocalActivityResultRegistryOwner，此时回退到 Trampoline Activity 调起系统选择器。
 */
@Composable
internal fun rememberAttachmentPickerLaunchers(
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
): AttachmentPickerLaunchers {
    val context = LocalContext.current
    val registryOwner = androidx.activity.compose.LocalActivityResultRegistryOwner.current

    val photoPicker = if (registryOwner != null) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                onAttachImage(uri.toString())
            }
        }
    } else null
    val videoPicker = if (registryOwner != null) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                onAttachVideo(uri.toString())
            }
        }
    } else null
    val filePicker = if (registryOwner != null) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            if (uris.isNotEmpty()) onAttachFiles(uris.map { it.toString() })
        }
    } else null
    val folderPicker = if (registryOwner != null) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                onAttachFolder(uri.toString())
            }
        }
    } else null
    return AttachmentPickerLaunchers(
        pickImage = {
            if (photoPicker != null) {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            } else {
                AgentAttachmentPickerTrampolineActivity.pickImages(context) { uris ->
                    uris.forEach { onAttachImage(it) }
                }
            }
        },
        pickVideo = {
            if (videoPicker != null) {
                videoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
            } else {
                AgentAttachmentPickerTrampolineActivity.pickVideo(context) { uri ->
                    onAttachVideo(uri)
                }
            }
        },
        pickFiles = {
            if (filePicker != null) {
                filePicker.launch(arrayOf("*/*"))
            } else {
                AgentAttachmentPickerTrampolineActivity.pickFiles(context) { uris ->
                    onAttachFiles(uris)
                }
            }
        },
        pickFolder = {
            if (folderPicker != null) {
                folderPicker.launch(null)
            } else {
                AgentAttachmentPickerTrampolineActivity.pickFolder(context) { uri ->
                    onAttachFolder(uri)
                }
            }
        },
    )
}

@Composable
internal fun AgentAttachmentPickerButton(
    launchers: AttachmentPickerLaunchers,
    onAttachFilePath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val menuState = rememberEtaMenuState()
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }

    val keepIme = rememberKeepImeWhenOpeningMenu()
    Box(modifier = modifier) {
        ChatInputNonFocusableIconButton(
            onClick = {
                keepIme()
                menuState.onAnchorClick()
            },
            contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(ChatInputActionIconSize),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        EtaDropdownMenu(
            expanded = menuState.expanded,
            onDismissRequest = menuState::dismiss,
            preferAbove = true,
            minWidth = 0.dp,
            focusable = false,
        ) {
            val options = listOf(
                Triple(stringResource(R.string.attachment_image), Icons.Outlined.Image, 0),
                Triple(stringResource(R.string.attachment_video), Icons.Outlined.Videocam, 1),
                Triple(stringResource(R.string.attachment_file), Icons.Rounded.Description, 2),
                Triple(stringResource(R.string.attachment_folder), Icons.Rounded.FolderOpen, 3),
                Triple(stringResource(R.string.attachment_enter_path), Icons.Outlined.Link, 4),
            )
            options.forEach { (option, icon, index) ->
                DropdownMenuItem(
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    text = { androidx.compose.material3.Text(option) },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = {
                        TouchHaptics.click(view)
                        menuState.dismiss()
                        when (index) {
                            0 -> launchers.pickImage()
                            1 -> launchers.pickVideo()
                            2 -> launchers.pickFiles()
                            3 -> launchers.pickFolder()
                            4 -> {
                                pathInput = ""
                                showPathDialog = true
                            }
                        }
                    },
                )
            }
        }
    }

    WindowDialog(
        show = showPathDialog,
        title = stringResource(R.string.ui_input_file_path_36d474),
        summary = stringResource(R.string.ui_supports_files_and_folders_under_internal_storage_or_520786),
        onDismissRequest = { showPathDialog = false },
    ) {
        Column {
            TextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = stringResource(R.string.ui_absolute_path_9ac6fc),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.attachment_add),
                confirmEnabled = pathInput.trim().startsWith('/'),
                onCancel = { showPathDialog = false },
                onConfirm = {
                    val path = pathInput.trim()
                    showPathDialog = false
                    onAttachFilePath(path)
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * 悬浮窗输入栏的附件入口按钮。
 *
 * 与主界面 [AgentAttachmentPickerButton] 共用同一套 [rememberAttachmentPickerLaunchers]：
 * 浮窗窗口没有 LocalActivityResultRegistryOwner，因此会自动回退到 Trampoline Activity。
 * 按钮可见尺寸沿用浮窗输入栏其它圆钮的 36dp，保持栏内按钮统一。
 */
@Composable
internal fun OverlayAttachmentPickerButton(
    onAttachImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val launchers = rememberAttachmentPickerLaunchers(
        onAttachImage = onAttachImage,
        onAttachVideo = {},
        onAttachFiles = onAttachFiles,
        onAttachFolder = onAttachFolder,
    )
    var showMenu by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    Box(modifier = modifier) {
        IconButton(
            onClick = {
                TouchHaptics.click(view)
                showMenu = true
            },
            minWidth = 36.dp,
            minHeight = 36.dp,
            cornerRadius = 18.dp,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.ui_add_attachment_dba9e8),
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
        EtaDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            preferAbove = true,
            minWidth = 0.dp,
            focusable = false,
        ) {
            val options = listOf(
                Triple(stringResource(R.string.attachment_image), Icons.Outlined.Image, 0),
                Triple(stringResource(R.string.attachment_file), Icons.Rounded.Description, 1),
                Triple(stringResource(R.string.attachment_folder), Icons.Rounded.FolderOpen, 2),
                Triple(stringResource(R.string.attachment_enter_path), Icons.Outlined.Link, 3),
            )
            options.forEach { (option, icon, index) ->
                DropdownMenuItem(
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    text = { androidx.compose.material3.Text(option) },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    onClick = {
                        TouchHaptics.click(view)
                        showMenu = false
                        when (index) {
                            0 -> launchers.pickImage()
                            1 -> launchers.pickFiles()
                            2 -> launchers.pickFolder()
                            3 -> showPathDialog = true
                        }
                    },
                )
            }
        }
    }
    WindowDialog(
        show = showPathDialog,
        title = stringResource(R.string.ui_input_file_path_36d474),
        summary = stringResource(R.string.ui_supports_files_and_folders_under_internal_storage_or_520786),
        onDismissRequest = { showPathDialog = false },
    ) {
        Column {
            TextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                label = stringResource(R.string.ui_absolute_path_9ac6fc),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.attachment_add),
                confirmEnabled = pathInput.trim().startsWith('/'),
                onCancel = { showPathDialog = false },
                onConfirm = {
                    val path = pathInput.trim()
                    showPathDialog = false
                    onAttachFilePath(path)
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
internal fun PendingFileReferenceStrip(
    references: List<PendingFileReferenceUi>,
    onRemoveReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        references.forEach { pending ->
            val reference = pending.reference
            Row(
                modifier = Modifier
                    .height(42.dp)
                    .widthIn(max = 250.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh,
                        cornerRadius = 14.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cornerRadius = 14.dp,
                    )
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onRemoveReference(pending.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_file_reference_04bbfc),
                        modifier = Modifier.size(15.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SentFileReferenceFlow(
    references: List<AgentFileReference>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        references.forEach { reference ->
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .widthIn(max = 280.dp)
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surface,
                        cornerRadius = 12.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.45f),
                        cornerRadius = 12.dp,
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (reference.kind == AgentFileReferenceKind.Directory) {
                        Icons.Rounded.FolderOpen
                    } else {
                        Icons.Rounded.Description
                    },
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
                Text(
                    text = reference.displayName +
                        if (reference.kind == AgentFileReferenceKind.Directory) "/" else "",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal class InputPopupPositionProvider(
    private val inputContainerTopPx: Int,
    private val windowHorizontalInsetPx: Int? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val alignToEnd = when (alignment) {
            PopupPositionProvider.Align.End,
            PopupPositionProvider.Align.TopEnd,
            PopupPositionProvider.Align.BottomEnd,
            -> true

            else -> false
        }
        val physicalEnd = if (layoutDirection == LayoutDirection.Ltr) alignToEnd else !alignToEnd
        val requestedX = when {
            windowHorizontalInsetPx != null && physicalEnd ->
                windowBounds.right - popupContentSize.width - popupMargin.right - windowHorizontalInsetPx

            windowHorizontalInsetPx != null ->
                windowBounds.left + popupMargin.left + windowHorizontalInsetPx

            physicalEnd -> anchorBounds.right - popupContentSize.width - popupMargin.right
            else -> anchorBounds.left + popupMargin.left
        }
        val maxX = (windowBounds.right - popupContentSize.width - popupMargin.right)
            .coerceAtLeast(windowBounds.left)
        val requestedY = inputContainerTopPx - popupContentSize.height - popupMargin.bottom
        val maxY = windowBounds.bottom - popupContentSize.height - popupMargin.bottom
        val minY = (windowBounds.top + popupMargin.top).coerceAtMost(maxY)
        return IntOffset(
            x = requestedX.coerceIn(windowBounds.left, maxX),
            y = requestedY.coerceIn(minY, maxY),
        )
    }

    override fun getMargins(): PaddingValues = PaddingValues(vertical = ChatInputPopupMargin)
}
