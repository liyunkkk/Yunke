package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Chat : AppRoute

    @Serializable
    data object Browser : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object AgentTaskPreference : AppRoute

    @Serializable
    // Deserialization compatibility only; renders AgentTaskPreference, with no separate entry in UI.
    data object VirtualDisplayRecovery : AppRoute

    @Serializable
    data object Haptics : AppRoute

    @Serializable
    data object Skills : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    /** 系统助手接管：默认助手、厂商入口、Gemini 与一圈即搜（自设置页抽出的二级页）。 */
    @Serializable
    data object AssistantTakeover : AppRoute

    @Serializable
    data object Settings : AppRoute

    /** 设置二级页：模型与对话（从设置首页抽出）。 */
    @Serializable
    data object SettingsModel : AppRoute

    /** 设置二级页：扩展与角色（从设置首页抽出）。 */
    @Serializable
    data object SettingsExtensions : AppRoute

    /** 设置二级页：工具（从设置首页抽出）。 */
    @Serializable
    data object SettingsTools : AppRoute

    /** 设置二级页：通用（从设置首页抽出）。 */
    @Serializable
    data object SettingsGeneral : AppRoute

    /** 设置二级页：诊断（从设置首页抽出）。 */
    @Serializable
    data object SettingsDiagnostics : AppRoute

    /** 设置二级页：关于（从设置首页抽出）。 */
    @Serializable
    data object SettingsAbout : AppRoute

    // 注意：不新增 SettingsPermissions / SettingsAssistantTakeover 两个路由。
    // 权限与通知直接复用已有的 AppRoute.Permissions（PermissionHealthScreen，
    // 比 Number444 的 4 行简化页完整得多）；系统接管直接复用已有的
    // AppRoute.AssistantTakeover（SystemTakeoverSettingsScreen）。
    @Serializable
    data object SpeechSettings : AppRoute

    @Serializable
    data object TtsSettings : AppRoute

    @Serializable
    data object VoiceSettings : AppRoute

    @Serializable
    data object AuxiliaryVision : AppRoute

    @Serializable
    data object TitleModel : AppRoute

    @Serializable
    data object ErrorReconnectSettings : AppRoute

    @Serializable
    data object SubAgents : AppRoute

    @Serializable
    data object VoiceModeSettings : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    @Serializable
    data object WhaleMaid : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    @Serializable
    data object Workspace : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object McpServers : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderAuthMethod(val providerType: NewProviderType) : AppRoute

    @Serializable
    data class ModelProviderNew(
        val providerType: NewProviderType,
        val authMode: String = "api_key",
    ) : AppRoute

    @Serializable
    data object ContextCompression : AppRoute

    @Serializable
    data class Assistants(val picker: Boolean = false) : AppRoute

    @Serializable
    data class AssistantEdit(val assistantId: String) : AppRoute

    @Serializable
    data object UsageStats : AppRoute

    @Serializable
    data object ManageChats : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, Anthropic, Antigravity, DoubaoSpeech, CompatibleSpeech }
