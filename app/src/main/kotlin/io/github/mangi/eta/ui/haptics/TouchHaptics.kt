package io.github.mangi.eta.ui.haptics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.config.Prefs

/**
 * 工作过程里「正在运行」的工具步骤只震动一次，避免 LazyColumn 回收或状态刷新重复触发。
 */
internal class LiveToolHapticTracker(
    private val maxIds: Int = 256,
) {
    private val ids = LinkedHashSet<String>()

    fun markIfNew(id: String): Boolean {
        if (id.isBlank()) return false
        if (!ids.add(id)) return false
        while (ids.size > maxIds) {
            val iterator = ids.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return true
    }
}

/**
 * App 内触控反馈。默认档走系统 [View.performHapticFeedback]，以便 HyperOS / 线性马达按系统主题渲染。
 * 低 / 中 / 高档用自定义振幅。总开关关闭时不再发振；同时也尊重系统「触控反馈」总开关。
 */
internal object TouchHaptics {
    private val liveToolTracker = LiveToolHapticTracker()
    private const val GENERATION_TICK_INTERVAL_MS = 32L
    private var lastGenerationTickAt = 0L

    @Volatile
    private var cachedIntensity: HapticIntensity? = null

    fun isTouchEnabled(): Boolean = Prefs.isEnabled(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK)

    fun isMessageGenerationEnabled(): Boolean =
        isTouchEnabled() && Prefs.isEnabled(Prefs.Keys.HAPTIC_MESSAGE_GENERATION)

    fun currentIntensity(): HapticIntensity {
        cachedIntensity?.let { return it }
        val loaded = HapticIntensity.fromWire(
            Prefs.getString(Prefs.Keys.HAPTIC_INTENSITY, HapticIntensity.DEFAULT.wireValue),
        )
        cachedIntensity = loaded
        return loaded
    }

    fun setIntensity(value: HapticIntensity): Boolean {
        val prefs = Prefs.localAgentPreferences() ?: return false
        val ok = runCatching {
            prefs.edit().putString(Prefs.Keys.HAPTIC_INTENSITY, value.wireValue).commit()
        }.getOrDefault(false)
        if (ok) cachedIntensity = value
        return ok
    }

    fun reloadIntensity() {
        cachedIntensity = HapticIntensity.fromWire(
            Prefs.getString(Prefs.Keys.HAPTIC_INTENSITY, HapticIntensity.DEFAULT.wireValue),
        )
    }

    /**
     * 流式打字的轻触。间隔过短时马达会吞掉后续 tick，输出越快越像没在跟。
     * 32ms 大约一帧半，快流也能连成一串，又不会把 HyperOS 的 tick 挤掉。
     */
    fun generationTick(view: View?) {
        if (!isMessageGenerationEnabled()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastGenerationTickAt < GENERATION_TICK_INTERVAL_MS) return
        lastGenerationTickAt = now
        tick(view)
    }

    /** 推理、终端、读图、网页搜索等标签首次出现时轻触一次。 */
    fun onLiveToolActivity(view: View?, toolId: String) {
        if (!liveToolTracker.markIfNew(toolId)) return
        generationTick(view)
    }

    fun click(view: View?) {
        perform(view, HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun previewClick(view: View?, intensity: HapticIntensity) {
        perform(
            view,
            HapticFeedbackConstants.CONTEXT_CLICK,
            intensity = intensity,
            ignoreAppSwitch = true,
        )
    }

    fun keyboardTap(view: View?) {
        perform(view, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun longPress(view: View?) {
        perform(view, HapticFeedbackConstants.LONG_PRESS)
    }

    fun tick(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        perform(view, constant)
    }

    fun gestureThreshold(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        perform(view, constant)
    }

    fun confirm(view: View?) {
        val constant = if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        perform(view, constant)
    }

    private fun perform(
        view: View?,
        constant: Int,
        intensity: HapticIntensity = currentIntensity(),
        ignoreAppSwitch: Boolean = false,
    ) {
        if (view == null) return
        if (!ignoreAppSwitch && !isTouchEnabled()) return
        if (intensity == HapticIntensity.DEFAULT) {
            // ColorOS / HyperOS 单参数入口会走线性马达主题；带 flags=0 的双参数
            // 会再检查 View.isHapticFeedbackEnabled，升级后这个标志经常是关的。
            if (ignoreAppSwitch) {
                view.performHapticFeedback(
                    constant,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
                )
            } else {
                view.performHapticFeedback(constant)
            }
            return
        }
        vibrateScaled(view, constant, intensity)
    }

    private fun vibrateScaled(view: View, constant: Int, intensity: HapticIntensity) {
        val context = view.context
        if (!isSystemHapticEnabled(context)) return
        val vibrator = context.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?: return
        if (!vibrator.hasVibrator()) return
        val isTick = constant == HapticFeedbackConstants.CLOCK_TICK ||
            constant == HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        val primitiveId = if (isTick) {
            VibrationEffect.Composition.PRIMITIVE_TICK
        } else {
            VibrationEffect.Composition.PRIMITIVE_CLICK
        }
        val scale = if (isTick) intensity.tickScale else intensity.clickScale
        val amplitude = if (isTick) intensity.tickAmplitude else intensity.clickAmplitude
        val durationMs = if (isTick) 10L else 16L
        runCatching {
            val supportsPrimitive = vibrator
                .arePrimitivesSupported(primitiveId)
                .firstOrNull() == true
            val effect = if (supportsPrimitive) {
                VibrationEffect.startComposition()
                    .addPrimitive(primitiveId, scale)
                    .compose()
            } else {
                VibrationEffect.createOneShot(durationMs, amplitude)
            }
            // No cancel(): it would also stop unrelated vibrations from the system and other apps
            // on the shared default vibrator. Overlapping short ticks are harmless.
            vibrator.vibrate(
                effect,
                VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build(),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun isSystemHapticEnabled(context: Context): Boolean =
        runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)
}

@Composable
internal fun ApplyTouchHapticFeedbackEnabled() {
    val view = LocalView.current
    val prefs = remember { Prefs.localAgentPreferences() }
    var enabled by remember {
        mutableStateOf(TouchHaptics.isTouchEnabled())
    }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.HAPTIC_TOUCH_FEEDBACK) {
                enabled = TouchHaptics.isTouchEnabled()
            } else if (key == Prefs.Keys.HAPTIC_INTENSITY) {
                TouchHaptics.reloadIntensity()
            }
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        enabled = TouchHaptics.isTouchEnabled()
        TouchHaptics.reloadIntensity()
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SideEffect {
        if (view.isHapticFeedbackEnabled != enabled) {
            view.isHapticFeedbackEnabled = enabled
        }
    }
}
