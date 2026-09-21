package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文、应用入口与屏幕观察工具 schema。 */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "获取手机当前时间、时区和最近系统位置；涉及现在、今天、明天或所在位置时调用。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "搜索手机上已安装的 Android 应用，返回应用名和包名。打开应用前如果不确定包名，先调用这个工具。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用名或包名片段，例如 QQ、微信、com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "是否包含系统应用，默认 false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 20 个结果，默认 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "启动一个已安装 Android 应用。优先提供 package_name；只有应用名时允许模糊匹配，匹配多个会返回候选而不会启动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "精确 Android 包名，例如 com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "应用显示名，例如 QQ")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "把一个确定有效的 URI 显式交给 Android 外部应用处理，例如 https、tel、geo 或应用 deep link。它不用于读取网页或网页交互。不要编造 URI。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "确定有效、可由系统处理的 URI")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "观察当前手机屏幕，默认只返回前台应用、屏幕尺寸、observation_id 与可见 UI 节点，不附截图。节点为空、目标无法唯一识别、界面以 Canvas/地图/图片/二维码等视觉内容为主，或任务依赖颜色、图像、空间布局时，显式设置 include_screenshot=true；补截图时保持 include_ui_tree=true，以同一次新观察刷新节点和 observation_id，禁止把新截图与旧节点混用。节点动作必须原样携带同一次观察的 observation_id；树被截断但节点语义仍有效时，优先把 max_nodes 提高到 120 后重试，不要仅因截断请求截图。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "是否附加当前屏幕原图给模型，默认 false；仅在 UI 节点不足以完成任务时显式开启")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "是否返回 UI 节点列表，默认 true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "最多返回 1 到 120 个 UI 节点，默认 60")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "delegate_to_kimi_code",
                    description = "将复杂代码编写、重构或项目文件批量修改任务委派给内置的 Kimi Code 编程子代理执行。子代理运行在隔离的 Linux 环境中，能够就地读写代码、运行测试并汇报变更。适用于多文件代码修改、逻辑重构、写脚本等重型编码任务。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "task",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "具体的重构或编码任务描述与指令")
                                )
                                .put(
                                    "project_path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "工作目录绝对路径，默认 /workspace")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "执行超时时间（秒），默认 120，范围 10 到 600")
                                )
                                .put(
                                    "conversation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "description",
                                            "会话绑定键，同一对话的多轮委派复用同一个 Kimi 上下文；" +
                                                "通常无需填写，由运行时自动注入当前对话 id"
                                        )
                                )
                                .put(
                                    "conversation_title",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Kimi 会话标题，便于在 Kimi 面板中定位；缺省用工作目录名")
                                )
                        )
                        .put("required", JSONArray().put("task"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "get_assistant_config",
                    description = "获取芸珂自身当前的运行状态、模型配置与功能开关（已自动进行脱敏保护）。当用户询问当前使用的是什么模型、中转站/服务商、思考模式开关或自身设置时调用此工具直接静默读取，严禁操控屏幕去设置界面翻找。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "update_assistant_config",
                    description = "安全修改芸珂自身的部分运行设置或切换已配置的模型/服务商。仅支持白名单受控项，严禁传入敏感 API Key/Token 明文。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("switch_model").put("switch_provider").put("toggle_setting"))
                                        .put("description", "修改动作类型：switch_model（切换模型）、switch_provider（切换服务商）、toggle_setting（切换开关）")
                                )
                                .put(
                                    "target",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "目标标识：若为 switch_model 则传入 model_id 或模型名称；若为 switch_provider 则传入 provider_id 或服务商名称；若为 toggle_setting 则传入开关名称（如 thinking_mode, terminal_tools, browser_tools, device_direct_tools, device_sensitive_read_tools, device_sensitive_action_tools, kimi_builtin_browser）")
                                )
                                .put(
                                    "value",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "开关设置的新值，例如 \"true\" 或 \"false\"（仅 toggle_setting 时需要）")
                                )
                        )
                        .put("required", JSONArray().put("action").put("target"))
                )
            )
    }
}
