package io.github.mangi.eta.ui.pages.providers

import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.BalanceOption
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderBalanceFetcher
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.data.repository.formatBalanceDisplay
import io.github.mangi.eta.ui.icons.MoneyBag02
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 从 UI 触发的余额刷新需要一个长生命周期协程作用域：composition 随页面销毁会取消在途请求。
 * 这里沿 Context 包裹链向上寻找宿主 Activity 的 LifecycleOwner，拿它的 lifecycleScope。
 */
internal fun Context.activityLifecycleOwnerOrNull(): LifecycleOwner? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    return current as? LifecycleOwner
}

/** 下拉框顺序与预设一一对应。 */
private val BalancePresets = listOf(
    BalanceOption.PRESET_CUSTOM,
    BalanceOption.PRESET_NEW_API,
    BalanceOption.PRESET_SUB2API,
    BalanceOption.PRESET_DEEPSEEK,
)

@Composable
internal fun ProviderBalanceOptionFields(
    balanceOption: BalanceOption,
    onBalanceOptionChange: (BalanceOption) -> Unit,
    provider: ProviderSetting? = null,
) {
    var expanded by remember { mutableStateOf(balanceOption.enabled) }
    var locallyEnabled by remember { mutableStateOf(balanceOption.enabled) }
    val context = LocalContext.current
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var accessTokenVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resolved = balanceOption.copy(enabled = locallyEnabled || balanceOption.enabled).resolved()
    val isNewApi = resolved.preset == BalanceOption.PRESET_NEW_API
    val switchOn = locallyEnabled || balanceOption.enabled

    Column(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.ui_balance_info_title),
            summary = stringResource(R.string.ui_balance_info_summary),
            onClick = { expanded = !expanded },
            endActions = {
                androidx.compose.material3.Switch(
                    checked = switchOn,
                    onCheckedChange = {
                        locallyEnabled = it
                        onBalanceOptionChange(balanceOption.copy(enabled = it))
                        if (it) expanded = true
                    }
                )
            }
        )

        AnimatedVisibility(visible = expanded) {
            Column {
                val presetIndex = BalancePresets.indexOf(resolved.preset).coerceAtLeast(0)
                WindowSpinnerPreference(
                    items = listOf(
                        DropdownItem(text = stringResource(R.string.ui_balance_preset_custom)),
                        DropdownItem(text = stringResource(R.string.ui_balance_preset_new_api)),
                        DropdownItem(text = stringResource(R.string.ui_balance_preset_sub2api)),
                        DropdownItem(text = stringResource(R.string.ui_balance_preset_deepseek)),
                    ),
                    selectedIndex = presetIndex,
                    title = stringResource(R.string.ui_balance_preset),
                    summary = stringResource(
                        when (BalancePresets[presetIndex]) {
                            BalanceOption.PRESET_NEW_API -> R.string.ui_balance_preset_new_api_summary
                            BalanceOption.PRESET_SUB2API -> R.string.ui_balance_preset_sub2api_summary
                            BalanceOption.PRESET_DEEPSEEK -> R.string.ui_balance_preset_deepseek_summary
                            else -> R.string.ui_balance_preset_custom_summary
                        },
                    ),
                    onSelectedIndexChange = { selectedIndex ->
                        onBalanceOptionChange(
                            BalanceOption.applyPreset(
                                BalancePresets.getOrElse(selectedIndex) { BalanceOption.PRESET_CUSTOM },
                                balanceOption.copy(enabled = switchOn),
                            ),
                        )
                    },
                )
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    if (isNewApi) {
                        TextField(
                            value = balanceOption.accessToken,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(accessToken = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_access_token),
                            singleLine = true,
                            visualTransformation = if (accessTokenVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { accessTokenVisible = !accessTokenVisible }) {
                                    Icon(
                                        imageVector = if (accessTokenVisible) {
                                            Icons.Rounded.Visibility
                                        } else {
                                            Icons.Rounded.VisibilityOff
                                        },
                                        contentDescription = if (accessTokenVisible) {
                                            context.getString(R.string.page_hide_bb0e7e)
                                        } else {
                                            context.getString(R.string.page_show_71b677)
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.ui_balance_access_token_summary),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                        )
                    } else {
                        TextField(
                            value = balanceOption.apiPath,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(apiPath = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_api_path),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TextField(
                            value = balanceOption.resultPath,
                            onValueChange = {
                                onBalanceOptionChange(balanceOption.copy(resultPath = it, enabled = switchOn))
                            },
                            label = stringResource(R.string.ui_balance_result_path),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    TextButton(
                        text = if (isTesting) {
                            context.getString(R.string.page_testing_f43705)
                        } else {
                            stringResource(R.string.ui_test_balance)
                        },
                        enabled = switchOn && !isTesting && provider != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val currentProvider = provider ?: return@TextButton
                            scope.launch {
                                isTesting = true
                                testResult = null
                                val option = balanceOption.copy(enabled = true).resolved()
                                testResult = if (option.apiPath.isBlank() || option.resultPath.isBlank()) {
                                    context.getString(R.string.ui_balance_test_missing_fields)
                                } else {
                                    ProviderBalanceFetcher.fetch(currentProvider, option)
                                        .fold(
                                            onSuccess = { formatBalanceDisplay(it) },
                                            onFailure = { it.message ?: it.toString() },
                                        )
                                }
                                isTesting = false
                            }
                        }
                    )
                    testResult?.let {
                        ProviderBalanceAmount(
                            amount = it,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProviderBalanceAmount(
    amount: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = MoneyBag02,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(12.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = amount,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 只有拿到过成功金额才占用空间。
 *
 * 余额刷新在后台静默进行：既没有“刷新中”，也不显示“已刷新”之类的成功提示，
 * 无缓存金额的失败同样不再插入提示文本，避免刷新过程在顶部栏与模型选择器里闪烁。
 */
internal fun hasBalanceIndicatorContent(state: ProviderBalanceState?): Boolean =
    state?.amount != null

/**
 * 顶部栏与模型选择器共用的余额只读指示器。
 *
 * - 只展示最后一次成功金额；刷新继续在后台更新数字，但不再显示刷新中/已刷新等提示。
 * - 金额与图标默认使用主题中性文字色，缓存过期或刷新失败不改变颜色。
 * - 只读：不响应点击，也不弹出余额详情。
 * - [animateChanges] 为 true 时（仅顶部栏），余额变化会播放一次动画：
 *   扣费不足 1 时在金额下方飘出小号差值；扣费满 1 时数字滚动到新值、差值加粗弹出、
 *   图标抖动并短暂变红；余额上升只飘出绿色差值。动画只作用于绘制层，不改变指示器尺寸；
 *   过时事件或系统关闭动画时不播放，播放中再次变化会合并为一个差值。
 */
@Composable
internal fun ProviderBalanceIndicator(
    state: ProviderBalanceState?,
    modifier: Modifier = Modifier,
    animateChanges: Boolean = false,
) {
    if (state == null) return
    val amount = state.amount ?: return
    if (animateChanges) {
        AnimatedProviderBalanceIndicator(state = state, amount = amount, modifier = modifier)
        return
    }
    val amountColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = MoneyBag02,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(12.dp),
            tint = amountColor,
        )
        Text(
            text = amount,
            style = MiuixTheme.textStyles.footnote1,
            color = amountColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 余额变化动画的强度档位。 */
internal enum class BalanceEffectLevel { Increase, Light, Strong }

/** 超过这个时长才被 UI 看到的变化不再播放。 */
private const val BalanceChangeStaleMs = 5_000L

/** 扣费达到这个金额即使用强档动画。 */
private val StrongDeductionThreshold: BigDecimal = BigDecimal.ONE

private val BalanceIncreaseColor = Color(0xFF2DA44E)

/** 按两位小数后为 0 的变化不值得提示，返回 null。 */
internal fun balanceEffectLevel(delta: BigDecimal): BalanceEffectLevel? {
    val rounded = delta.setScale(2, RoundingMode.HALF_UP)
    return when {
        rounded.signum() == 0 -> null
        rounded.signum() > 0 -> BalanceEffectLevel.Increase
        rounded.negate() >= StrongDeductionThreshold -> BalanceEffectLevel.Strong
        else -> BalanceEffectLevel.Light
    }
}

/** 形如 "-0.52"、"+5.00"；不使用千分位，保持飘字短小。 */
internal fun formatBalanceDelta(delta: BigDecimal): String {
    val magnitude = delta.abs().setScale(2, RoundingMode.HALF_UP).toPlainString()
    return if (delta.signum() < 0) "-$magnitude" else "+$magnitude"
}

private fun Context.animatorsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

private data class BalanceBurst(val delta: BigDecimal, val level: BalanceEffectLevel)

@Composable
private fun AnimatedProviderBalanceIndicator(
    state: ProviderBalanceState,
    amount: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val neutralColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val decreaseColor = MiuixTheme.colorScheme.error
    val change = state.lastChange

    // 首次组合时把已有变化视为已看过，避免打开页面就重播旧事件。
    // 不用 rememberSaveable：进程重建后 seq 从头计数，恢复旧值会吞掉新事件。
    var lastSeenSeq by remember { mutableLongStateOf(change?.seq ?: 0L) }
    var burst by remember { mutableStateOf<BalanceBurst?>(null) }
    var rollingActive by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf(false) }
    val rolling = remember { Animatable(0f) }
    val amountShift = remember { Animatable(0f) }
    val iconRotation = remember { Animatable(0f) }
    val floatOffset = remember { Animatable(0f) }
    val floatAlpha = remember { Animatable(0f) }
    val floatScale = remember { Animatable(1f) }
    val amountColor by animateColorAsState(
        targetValue = if (highlight) decreaseColor else neutralColor,
        animationSpec = tween(durationMillis = if (highlight) 150 else 800),
        label = "balanceAmountColor",
    )

    LaunchedEffect(change?.seq) {
        val incoming = change ?: return@LaunchedEffect
        if (incoming.seq <= lastSeenSeq) return@LaunchedEffect
        lastSeenSeq = incoming.seq
        // 新事件会取消上一次播放；未播完的差值在这里合并。
        val merged = (burst?.delta ?: BigDecimal.ZERO) + incoming.delta
        val level = balanceEffectLevel(merged)
        val stale = System.currentTimeMillis() - incoming.atMillis > BalanceChangeStaleMs
        // 被打断的上一次播放可能停在中途，先复位再决定是否播放。
        rollingActive = false
        highlight = false
        iconRotation.snapTo(0f)
        amountShift.snapTo(0f)
        if (level == null || stale || !context.animatorsEnabled()) {
            burst = null
            floatAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        burst = BalanceBurst(merged, level)
        floatOffset.snapTo(0f)
        floatScale.snapTo(1f)
        floatAlpha.snapTo(1f)
        val riseDistance = with(density) { (if (level == BalanceEffectLevel.Strong) 8.dp else 6.dp).toPx() }
        coroutineScope {
            launch {
                floatOffset.animateTo(
                    targetValue = -riseDistance,
                    animationSpec = tween(durationMillis = 1_300, easing = LinearOutSlowInEasing),
                )
            }
            launch {
                delay(if (level == BalanceEffectLevel.Strong) 900L else 550L)
                floatAlpha.animateTo(0f, tween(durationMillis = 600))
            }
            val target = state.amountValue
            if (level == BalanceEffectLevel.Strong) {
                launch {
                    floatScale.animateTo(1.2f, tween(durationMillis = 120))
                    floatScale.animateTo(
                        1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    )
                }
                launch {
                    iconRotation.snapTo(0f)
                    iconRotation.animateTo(
                        targetValue = 0f,
                        animationSpec = keyframes {
                            durationMillis = 480
                            12f at 60
                            -12f at 140
                            10f at 220
                            -8f at 300
                            4f at 380
                        },
                    )
                }
                launch {
                    highlight = true
                    delay(500L)
                    highlight = false
                }
                if (target != null) {
                    launch {
                        rolling.snapTo((target - merged).toFloat())
                        rollingActive = true
                        rolling.animateTo(
                            targetValue = target.toFloat(),
                            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                        )
                        rollingActive = false
                    }
                }
            } else {
                launch {
                    amountShift.snapTo(1f)
                    amountShift.animateTo(0f, tween(durationMillis = 260, easing = LinearOutSlowInEasing))
                }
            }
        }
        burst = null
    }

    // 强档事件被 LaunchedEffect 接手前，先显示旧值，避免新数字闪一帧再滚动。
    val pendingStrongFrom = change
        ?.takeIf { it.seq > lastSeenSeq }
        ?.let { incoming ->
            val target = state.amountValue ?: return@let null
            val merged = (burst?.delta ?: BigDecimal.ZERO) + incoming.delta
            if (balanceEffectLevel(merged) == BalanceEffectLevel.Strong) target - merged else null
        }
    val displayText = when {
        rollingActive -> formatBalanceDisplay(rolling.value.toString())
        pendingStrongFrom != null -> formatBalanceDisplay(pendingStrongFrom.toPlainString())
        else -> amount
    }

    Box(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MoneyBag02,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(12.dp)
                    .graphicsLayer { rotationZ = iconRotation.value },
                tint = amountColor,
            )
            Text(
                text = displayText,
                style = MiuixTheme.textStyles.footnote1,
                color = amountColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer {
                    val shift = amountShift.value
                    translationY = shift * 4.dp.toPx()
                    alpha = 1f - 0.5f * shift
                },
            )
        }
        val current = burst
        if (current != null) {
            val strong = current.level == BalanceEffectLevel.Strong
            val baseStyle = MiuixTheme.textStyles.footnote1
            Text(
                text = formatBalanceDelta(current.delta),
                style = if (strong) baseStyle.copy(fontWeight = FontWeight.Bold) else baseStyle,
                color = if (current.level == BalanceEffectLevel.Increase) BalanceIncreaseColor else decreaseColor,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 零尺寸占位：飘字画在金额下方，不参与指示器测量，顶栏宽度不会跳动。
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints())
                        layout(0, 0) { placeable.placeRelative(-placeable.width, 0) }
                    }
                    .graphicsLayer {
                        translationY = floatOffset.value
                        alpha = floatAlpha.value
                        scaleX = floatScale.value
                        scaleY = floatScale.value
                        transformOrigin = TransformOrigin(1f, 0f)
                    }
                    .clearAndSetSemantics {},
            )
        }
    }
}
