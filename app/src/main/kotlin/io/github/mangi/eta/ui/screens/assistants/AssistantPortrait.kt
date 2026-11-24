package io.github.mangi.eta.ui.screens.assistants

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 竖版肖像卡比例与 Eta 角色卡一致：2:3，裁切保留人物上半身。 */
internal val AssistantPortraitAspect = 2f / 3f

/** 肖像卡与 Hero 头像共用的圆角。 */
internal val AssistantPortraitCorner = 20.dp

private val AssistantHeroAvatarWidth = 132.dp
private val AssistantHeroAvatarHeight = AssistantHeroAvatarWidth / AssistantPortraitAspect

/**
 * 助手库卡片：整张头像铺满，底部渐暗承载名字，与 Eta 角色库同构。
 *
 * 「当前使用」徽标替代 Eta 的收藏星标——Yunke 的助手没有收藏概念。
 * 长按仍走原来那份操作菜单（复制 / 编辑 / 删除）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantPortraitCard(
    profile: AssistantProfile,
    bitmap: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .aspectRatio(AssistantPortraitAspect)
            .clip(RoundedCornerShape(AssistantPortraitCorner))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                hapticFeedbackEnabled = false,
                onClick = {
                    TouchHaptics.click(view)
                    onClick()
                },
                onLongClick = {
                    TouchHaptics.longPress(view)
                    onLongClick()
                },
            ),
    ) {
        AssistantAvatar(
            bitmap = bitmap,
            size = null,
            shape = RectangleShape,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        )
        if (selected) {
            Text(
                text = stringResource(R.string.assistant_current),
                style = MiuixTheme.textStyles.footnote2,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.34f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            text = profile.name,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

/** 网格末尾的新建占位卡：与肖像卡同尺寸，视觉弱于真实助手。 */
@Composable
internal fun AssistantAddTile(
    enabled: Boolean,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .aspectRatio(AssistantPortraitAspect)
            .clip(RoundedCornerShape(AssistantPortraitCorner))
            .background(colors.surfaceContainer)
            .border(
                width = 0.5.dp,
                color = colors.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(AssistantPortraitCorner),
            )
            .clickable(enabled = enabled, onClick = onCreate),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = colors.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.assistant_add),
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
        }
    }
}

/**
 * 人格页主视觉：复刻 Eta `CharacterHero`。
 *
 * 头像放大后模糊铺满整块作氛围底色，再叠四段垂直渐变融回页面背景；前景是竖版 2:3 头像
 * （132×198dp、圆角 20dp）、右下角换头像按钮、名字与主操作。
 * 与 Eta 的差异只有两处：收藏星标换成「设为当前使用」，不搬「开始对话」。
 */
@Composable
internal fun AssistantHero(
    profile: AssistantProfile,
    bitmap: Bitmap?,
    isCurrent: Boolean,
    busy: Boolean,
    onChangeAvatar: () -> Unit,
    onSetCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth()) {
        if (bitmap != null) {
            // 只对一张已降采样的图做模糊，绘制成本固定，不随页面内容变化。
            AssistantAvatar(
                bitmap = bitmap,
                size = null,
                shape = RectangleShape,
                modifier = Modifier
                    .matchParentSize()
                    .blur(56.dp)
                    .alpha(0.4f),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    // 上下两端都融回页面底色：顶部不在顶栏下切出硬边，底部自然接到下方分组。
                    Brush.verticalGradient(
                        0f to colors.background,
                        0.18f to colors.background.copy(alpha = 0f),
                        0.6f to colors.background.copy(alpha = 0.2f),
                        1f to colors.background,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                AssistantAvatar(
                    bitmap = bitmap,
                    size = null,
                    shape = RoundedCornerShape(AssistantPortraitCorner),
                    modifier = Modifier
                        .width(AssistantHeroAvatarWidth)
                        .height(AssistantHeroAvatarHeight),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 10.dp, y = 10.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(0.5.dp, colors.outline.copy(alpha = 0.5f), CircleShape)
                        .clickable(enabled = !busy, onClick = onChangeAvatar),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoCamera,
                        contentDescription = "更换头像",
                        modifier = Modifier.size(18.dp),
                        tint = colors.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = profile.name,
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.assistant_current),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
            } else {
                TextButton(
                    text = "设为当前使用",
                    enabled = !busy,
                    onClick = onSetCurrent,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
