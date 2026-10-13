package io.github.mangi.eta.agent.display

import io.github.mangi.eta.config.Prefs

/**
 * 新一代虚拟屏（VirtualScreen，移植自 lejw0925/Eta-vivo）的设置与运行时钩子封装。
 *
 * vivo 原代码直接读 `SettingsDataStore.settings()` 的
 * virtualScreenEnabled / virtualScreenIdleTimeoutMinutes / virtualScreenOffEnabled /
 * virtualScreenAutoRestartApps 四个字段。
 *
 * Yunke 侧接入决策（Worker C，Phase 0，已验证）：
 * 走 `config/Prefs.kt` 的 SharedPreferences（`eta_agent_preferences`），而不是 DataStore。
 * 理由：① `AgentLocalTools.nextgenRouted()` 是非 suspend 纯函数、每个 tool call 都调，
 * 必须同步读；② `AgentLocalTools` 已有 `Prefs.isEnabled(Prefs.Keys.AGENT_*_TOOLS)` 的
 * 工具门禁先例；③ `Prefs.initLocal` 未完成时回退 BOOLEAN_DEFAULTS（本开关默认 false，
 * fail-closed，安全）；④ SwitchPref 设置页机制对 LOCAL_AGENT_KEYS 开箱即用。
 * DataStore 备选方案见 `phase0/SettingsKeys.kt.patch.md` §4（未采用）。
 *
 * 对接契约（key 名与 Worker A 约定一致）：
 * - Prefs key: "virtual_screen_nextgen_enabled"（Boolean，默认 false）
 * - Prefs key: "virtual_screen_nextgen_idle_minutes"（Int，默认 20）
 * - Prefs key: "virtual_screen_nextgen_off_enabled"（Boolean，默认 false）
 * - autoRestartApps 暂无对应 key，默认 false（vivo 侧是独立设置项，可后续补）。
 *
 * 注意：root helper 进程（RootDisplayCommandMain，独立 app_process）读不到 App 进程的
 * SharedPreferences；它所需的配置由 App 端在 create 动作参数里下发，本文件只在 App 进程消费。
 */
internal data class VirtualScreenNextgenSettings(
    val enabled: Boolean = false,
    val idleTimeoutMinutes: Int = 20,
    val offEnabled: Boolean = false,
    val autoRestartApps: Boolean = false,
)

internal object VirtualScreenNextgenSettingsStore {
    /**
     * 同步快路径：供 `AgentLocalTools.nextgenRouted()` 路由热路径使用（非 suspend，
     * 每个 tool call 调用一次，SharedPreferences 同步读无协程开销）。
     */
    fun isEnabledSync(): Boolean =
        Prefs.isEnabled(Prefs.Keys.VIRTUAL_SCREEN_NEXTGEN_ENABLED)

    /**
     * Worker A 移植的 VirtualScreenSession.kt 中 4 处 `runBlocking { current() }` 走这里。
     * Prefs 读取本身是同步的，suspend 只是为了兼容既有调用形状。
     */
    suspend fun current(): VirtualScreenNextgenSettings = VirtualScreenNextgenSettings(
        enabled = Prefs.isEnabled(Prefs.Keys.VIRTUAL_SCREEN_NEXTGEN_ENABLED),
        idleTimeoutMinutes = Prefs.getInt(
            Prefs.Keys.VIRTUAL_SCREEN_NEXTGEN_IDLE_MINUTES, 20,
        ).coerceIn(1, 120),
        offEnabled = Prefs.isEnabled(Prefs.Keys.VIRTUAL_SCREEN_NEXTGEN_OFF_ENABLED),
        autoRestartApps = false,
    )
}

/**
 * vivo 的 AgentRuntimeService.pauseVirtualScreen(resumeVirtualScreen) 在 Yunke 不存在：
 * Yunke 的 AgentRuntimeService 是一个 Service 类（agent/runtime/AgentRuntimeService.kt），
 * 只有私有的 requestPause()/requestResume()，没有公开的 virtual-screen 暂停接口。
 * 这里先做空实现保证编译；Worker C 决定真正的接入方式。
 */
internal object VirtualScreenRuntimeHooks {
    fun pauseVirtualScreen(runId: String?) {
        // TODO(Worker C): 接入 Yunke 的 run 暂停机制（候选：AgentRuntimeService 新增公开接口，
        // 或经 Messenger/Handler 发送暂停事件）。当前空实现：手动接管查看器时不暂停模型。
    }

    fun resumeVirtualScreen(runId: String?) {
        // TODO(Worker C): 同上，对应恢复。
    }
}
