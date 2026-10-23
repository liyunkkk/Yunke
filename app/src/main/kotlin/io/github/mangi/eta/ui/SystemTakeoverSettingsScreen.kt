package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.systemizer.GoogleAppSystemizerInstaller
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage

/**
 * 系统助手接管：默认助手、厂商入口、Gemini 与一圈即搜。
 *
 * 从设置首页抽出的二级页。设置页只保留一行入口，避免这几十行开关把诊断与权限挤到很下面。
 * 状态自持：服务到达、偏好变更与生命周期恢复都在本页内处理，不依赖首页的临时状态。
 */
@Composable
internal fun SystemTakeoverSettingsScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val capabilities = rememberDeviceCapabilities()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    var hasUsedSystemizer by remember { mutableStateOf(enhancementHistory.hasUsedSystemizer) }
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    val initialPreferences = remember {
        val service = EtaApp.serviceInstance
        InitialServicePreferenceSnapshot(service, Prefs.remotePreferencesForUi(service))
    }
    var prefs by remember { mutableStateOf(initialPreferences.initialValueForUi()) }
    var powerAssistantTarget by remember(prefs) {
        mutableStateOf(prefs?.let(Prefs::powerAssistantTarget) ?: enhancementHistory.powerTarget())
    }
    var etaAssistantActive by remember { mutableStateOf(isEtaAssistantActive(context)) }

    val openAssistantSettings: () -> Unit = {
        val failed = runCatching {
            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isFailure
        if (failed) {
            Toast.makeText(context, context.getString(R.string.settings_open_assistant_failed), Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(prefs) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose { }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
            if (key == Prefs.Keys.POWER_KEY_ASSISTANT_TARGET ||
                key == Prefs.Keys.POWER_KEY_TAKEOVER
            ) {
                powerAssistantTarget = Prefs.powerAssistantTarget(changedPrefs)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    DisposableEffect(Unit) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = initialPreferences.resolve(service) { currentService ->
                    Prefs.remotePreferencesForUi(currentService)
                }
                prefs?.let { connected ->
                    enhancementHistory.captureConnected(connected)
                    hasConnectedFramework = true
                }
                Prefs.reconcileAgentPreferences(service)
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                etaAssistantActive = isEtaAssistantActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val powerAssistantTargets = PowerAssistantTarget.entries
    val powerAssistantItems = powerAssistantTargets.map { target ->
        DropdownItem(text = target.displayName(context))
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_system_assistant_takes_over_f46043),
        onBack = onBack,
    ) {
        // ── 默认助手 ──────────────────────────────────────────────
        item(key = "takeover_default_assistant") {
            SmallTitle(stringResource(R.string.settings_default_assistant_section))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.ui_eta_system_assistant_003e9b),
                    summary = stringResource(
                        if (etaAssistantActive) {
                            R.string.settings_default_assistant_active
                        } else {
                            R.string.settings_select_default_assistant
                        },
                    ),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.AutoAwesome,
                        )
                    },
                    onClick = openAssistantSettings,
                )
                if (prefs != null || hasConnectedFramework) {
                    WindowSpinnerPreference(
                        title = stringResource(R.string.ui_long_press_the_power_button_1958d0),
                        items = powerAssistantItems,
                        selectedIndex = powerAssistantTargets.indexOf(powerAssistantTarget),
                        onSelectedIndexChange = { index ->
                            val target = powerAssistantTargets.getOrNull(index)
                                ?: return@WindowSpinnerPreference
                            val targetPrefs = prefs ?: return@WindowSpinnerPreference
                            if (putStringSync(
                                    prefs = targetPrefs,
                                    key = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET,
                                    value = target.persistedValue,
                                )
                            ) {
                                powerAssistantTarget = target
                                enhancementHistory.recordCommittedTarget(target)
                            } else {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_write_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        startAction = {
                            PreferenceIcon(
                                icon = Icons.Rounded.PowerSettingsNew,
                                enabled = prefs != null,
                            )
                        },
                        enabled = prefs != null,
                    )

                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_automatically_set_default_assistant_f86963),
                        summary = stringResource(R.string.ui_valid_only_for_gemini_and_eta_d5b63d),
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        icon = Icons.Rounded.Settings,
                    )
                }
            }
        }

        if (prefs != null || hasConnectedFramework) {
            // ── 厂商助手兼容入口 ──────────────────────────────────────────
            item(key = "takeover_oem_entrances") {
                SmallTitle(stringResource(R.string.ui_xiaobu_xiaoai_compatible_entrance_ae918a))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_enable_vendor_assistant_custom_models_c8e465),
                        key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                        icon = Icons.Rounded.Memory,
                    )

                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_only_take_over_with_agent_prefix_d17556),
                        key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                        icon = Icons.Rounded.Code,
                    )
                }
            }
        }

        if (prefs != null || hasConnectedFramework || capabilities.root.isGranted || hasUsedSystemizer) {
            // ── Gemini ─────────────────────────────────────────────────
            item(key = "takeover_gemini") {
                SmallTitle("Gemini")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    if (prefs != null || hasConnectedFramework) {
                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_maintain_hey_google_detection_after_screen_rest_9d6877),
                            key = Prefs.Keys.HOTWORD_SELF_HEAL,
                            icon = Icons.Rounded.Hearing,
                        )

                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_lock_screen_evokes_automatic_voice_input_1cde18),
                            key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                            icon = Icons.Rounded.Lock,
                        )

                        SwitchPref(
                            context = context,
                            prefs = prefs,
                            title = stringResource(R.string.ui_bright_screen_evokes_automatic_voice_input_4358fe),
                            key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                            icon = Icons.Rounded.Mic,
                        )
                    }
                    if (capabilities.root.isGranted || hasUsedSystemizer) {
                        ArrowPreference(
                            title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
                            startAction = {
                                PreferenceIcon(
                                    icon = Icons.Rounded.Inventory,
                                )
                            },
                            summary = if (capabilities.root.isGranted) null else stringResource(R.string.capability_root_required),
                            enabled = !installingSystemizer,
                            holdDownState = showSystemizerDialog,
                            onClick = {
                                if (!capabilities.root.isGranted) {
                                    onNavigate(AppRoute.SystemEnhance)
                                } else if (!installingSystemizer) {
                                    showSystemizerDialog = true
                                }
                            },
                        )
                    }
                }
            }
        }

        if (prefs != null || hasConnectedFramework) {
            // ── 一圈即搜 ────────────────────────────────────────────────
            item(key = "takeover_circle_to_search") {
                SmallTitle(stringResource(R.string.ui_search_in_one_turn_179584))
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_on_the_gesture_bar_triggers_a_circle_to_s_b80117),
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        icon = Icons.Rounded.SwipeUp,
                    )

                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = stringResource(R.string.ui_long_press_with_two_fingers_to_trigger_a_circle_sear_ab597a),
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        icon = Icons.Rounded.TouchApp,
                    )
                }
            }
        }
    }

    SystemizerConfirmDialog(
        show = showSystemizerDialog,
        installing = installingSystemizer,
        onDismissRequest = {
            if (!installingSystemizer) {
                showSystemizerDialog = false
            }
        },
        onConfirm = {
            if (installingSystemizer) return@SystemizerConfirmDialog
            if (!capabilities.root.isGranted) {
                showSystemizerDialog = false
                onNavigate(AppRoute.SystemEnhance)
                return@SystemizerConfirmDialog
            }
            enhancementHistory.recordSystemizerUse()
            hasUsedSystemizer = true
            showSystemizerDialog = false
            installingSystemizer = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    GoogleAppSystemizerInstaller(context.applicationContext).install()
                }
                installingSystemizer = false
                Toast.makeText(
                    context.applicationContext,
                    result.toToastMessage(context),
                    Toast.LENGTH_LONG,
                ).show()
            }
        },
    )
}
