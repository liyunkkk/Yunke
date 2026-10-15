package io.github.mangi.eta.ui.pages.providers

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.provider.ProviderLogoStore
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 供应商头像选择：内置图库 + 从相册选自定义图 + 恢复默认。 */
@Composable
internal fun ProviderAvatarPreference(
    providerId: String,
    current: String,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    // 订阅头像表：选完立刻在行内看到预览，不必返回列表确认。
    val logos by ProviderLogoStore.logos().collectAsState()
    val applied = logos[providerId].orEmpty()

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            val path = copyPickedImage(context, providerId, uri)
            if (path != null) onPick("file:$path")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderAvatarPreview(providerId = providerId, value = applied)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = stringResource(R.string.ui_provider_avatar),
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ui_provider_avatar_summary),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    if (showPicker) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_provider_avatar),
            onDismissRequest = { showPicker = false },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ProviderLogoStore.builtins.chunked(6).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { (slug, res) ->
                            val selected = current == "builtin:$slug"
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        onPick("builtin:$slug")
                                        showPicker = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(res),
                                    contentDescription = slug,
                                    modifier = Modifier
                                        .size(if (selected) 32.dp else 28.dp)
                                        .clip(CircleShape),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ui_provider_avatar_custom),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            imageLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                            showPicker = false
                        },
                    )
                    Text(
                        text = stringResource(R.string.ui_provider_avatar_default),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clickable {
                            onPick("")
                            showPicker = false
                        },
                    )
                }
            }
        }
    }
}


/** 头像预览：内置走资源，自定义走本地文件，空值退回默认图标。 */
@Composable
private fun ProviderAvatarPreview(providerId: String, value: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            value.startsWith("builtin:") ->
                ProviderLogoStore.builtinRes(value.removePrefix("builtin:"))?.let { res ->
                    Image(
                        painter = painterResource(res),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                    )
                }
            value.startsWith("file:") -> {
                val bitmap = remember(value) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(value.removePrefix("file:"))
                    }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                    )
                }
            }
        }
    }
}

/** 把选中的图片拷进应用私有目录，返回绝对路径；失败返回 null。 */
private fun copyPickedImage(context: Context, providerId: String, uri: android.net.Uri): String? =
    runCatching {
        val dir = java.io.File(context.filesDir, "provider_logos").apply { mkdirs() }
        val target = java.io.File(dir, "$providerId.png")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()
