package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明 YUNKe 自身可写配置的工具（模型服务商、子代理、MCP）。
 *
 * 目标是「运行本助手的 App 自己」：模型与服务商、子代理预设、MCP 服务器。
 * 系统设置由 `get_setting` / `set_setting` 负责，自身开关由 `get_assistant_config`
 * / `update_assistant_config` 负责，三者互不重叠。
 *
 * 移植自 EtaExpressive（Sam-Fic/EtaExpressive）的 AgentSelfSettingsToolCatalog，
 * 只取模型、子代理、MCP 三项；设置读写与历史管理未移植。
 */
internal object AgentSelfToolCatalog {
    const val MANAGE_MODELS = "eta_manage_models"
    const val MANAGE_SUBAGENTS = "eta_manage_subagents"
    const val CONFIGURE_MCP = "eta_configure_mcp"

    fun appendTo(tools: JSONArray) {
        tools

            .put(
                AgentToolSchema.function(
                    name = MANAGE_MODELS,
                    description = "Configure the models and providers of YUNKe, the assistant app you are running. " +
                        "action=list returns every provider with its models and the current selection and needs no other field. " +
                        "select sets the model used for chat (default), text-to-speech (tts), voice conversation (voice) or auto compression (compress). " +
                        "save_provider creates a provider when the name or base_url is new and otherwise updates that provider; models lists the model ids to keep and api_key is stored as a secret. " +
                        "save_model changes one existing model; only pass the fields the user asked about, and use auto to hand a capability back to whatever the provider reports. " +
                        "delete_model removes one model and delete_provider removes a provider you added, never a built-in one. " +
                        "fetch_models reads the provider's own model list to refresh the stored models and report the new ones it found. " +
                        "Every change applies from the next reply, not to the request you are answering now.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list")
                                                .put("select")
                                                .put("save_provider")
                                                .put("delete_provider")
                                                .put("save_model")
                                                .put("delete_model")
                                                .put("fetch_models"),
                                        )
                                        .put("description", "Required. What to do with a provider or a model."),
                                )
                                .put(
                                    "target",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("chat").put("tts").put("voice").put("compress"))
                                        .put("description", "select only: which feature owns the model. Default chat."),
                                )
                                .put(
                                    "provider",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Provider id or displayed name. Required by every action except list, which may omit it."),
                                )
                                .put(
                                    "model",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Model id or displayed name. Required by save_model and delete_model; select may omit it to keep the provider's current model."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "save_provider only: displayed provider name, also used to find an existing provider to update."),
                                )
                                .put(
                                    "base_url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "save_provider only: OpenAI compatible endpoint, for example https://api.example.com/v1."),
                                )
                                .put(
                                    "api_key",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "save_provider only: secret key for that endpoint. Omit it to keep the stored one, pass an empty string to clear it."),
                                )
                                .put(
                                    "models",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("maxItems", 60)
                                        .put("items", JSONObject().put("type", "string").put("maxLength", 200))
                                        .put("description", "save_provider only: model ids to keep on that provider. Missing ones are created; existing ones are left alone."),
                                )
                                .put(
                                    "is_enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "save_provider only: false hides the provider from the model picker without deleting it."),
                                )
                                .put(
                                    "display_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "save_model only: the name shown for this model."),
                                )
                                .put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "save_model only: false hides the model from the model picker without deleting it."),
                                )
                                .put(
                                    "vision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("on").put("off").put("auto"))
                                        .put("description", "save_model only: force whether this model can read images; auto uses the provider's answer."),
                                )
                                .put(
                                    "reasoning",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("on").put("off").put("auto"))
                                        .put("description", "save_model only: force whether this model can think before answering; auto uses the provider's answer."),
                                )
                                .put(
                                    "context_window",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 10_000_000)
                                        .put("description", "save_model only: context length in tokens, used for session clipping and context budgeting."),
                                )
                                .put(
                                    "reasoning_effort",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("off")
                                                .put("default")
                                                .put("minimal")
                                                .put("low")
                                                .put("medium")
                                                .put("high")
                                                .put("xhigh")
                                                .put("max"),
                                        )
                                        .put("description", "save_model only: how much thinking the model does by default. Only useful when the model supports reasoning."),
                                )
                                .put(
                                    "add_new",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "fetch_models only: also store the new models it found. Default false, which only refreshes the ones already stored."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = MANAGE_SUBAGENTS,
                    description = "Create and manage the sub-agent groups of YUNKe, the assistant app you are running. " +
                        "A group is a reusable preset of sub-agents that the main agent delegates to with delegate_task, and it can be applied to a conversation. " +
                        "action=list returns every group with its sub-agents and which group this conversation applied, and needs no other field. " +
                        "create_group, rename_group, delete_group and set_delegation manage a group itself, where set_delegation turns its automatic delegation on or off. " +
                        "add_agent, update_agent and remove_agent manage the sub-agents inside one group. " +
                        "apply copies a group onto this conversation. " +
                        "Only change what the user actually asked for, and state the result in your reply. " +
                        "Every change applies from the next reply, not to the answer you are writing now; editing a group does not change conversations that already applied it.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list")
                                                .put("create_group")
                                                .put("rename_group")
                                                .put("delete_group")
                                                .put("set_delegation")
                                                .put("add_agent")
                                                .put("update_agent")
                                                .put("remove_agent")
                                                .put("apply"),
                                        )
                                        .put("description", "Required. What to do with a group or one of its sub-agents."),
                                )
                                .put(
                                    "group",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "Group id or displayed name. Required by every action except list and create_group."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "create_group and rename_group only: the group's name, which is the new name when renaming."),
                                )
                                .put(
                                    "agent",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "update_agent and remove_agent only: the sub-agent's id or displayed name."),
                                )
                                .put(
                                    "agent_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "add_agent and update_agent only: the sub-agent's displayed name."),
                                )
                                .put(
                                    "role",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray().put("implementation").put("review").put("image_generation").put("video_generation"),
                                        )
                                        .put("description", "add_agent and update_agent only: what the sub-agent does. Default implementation."),
                                )
                                .put(
                                    "provider",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "add_agent and update_agent only: provider id or displayed name; pass it together with model to bind the sub-agent to a model."),
                                )
                                .put(
                                    "model",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "add_agent and update_agent only: model id or displayed name on that provider."),
                                )
                                .put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "set_delegation: the group's automatic delegation. add_agent and update_agent: whether this sub-agent is enabled; a new sub-agent starts enabled."),
                                )
                                .put(
                                    "task_tier",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("simple").put("regular").put("complex"))
                                        .put("description", "add_agent and update_agent only: task tier for role=implementation; ignored by other roles."),
                                )
                                .put(
                                    "reasoning",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("off")
                                                .put("default")
                                                .put("minimal")
                                                .put("low")
                                                .put("medium")
                                                .put("high")
                                                .put("xhigh")
                                                .put("max"),
                                        )
                                        .put("description", "add_agent and update_agent only: reasoning effort for this sub-agent; pass an empty string to clear it."),
                                )
                                .put(
                                    "image_resolution",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("low").put("medium").put("high").put("ultra"))
                                        .put("description", "add_agent and update_agent only: resolution for role=image_generation; pass an empty string to clear it."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = CONFIGURE_MCP,
                    description = "Configure the MCP servers of YUNKe, the assistant app you are running: the remote tool servers " +
                        "it connects to and which of their tools are offered. " +
                        "action=list returns every server with its endpoint, state and tools, and needs no other field. " +
                        "add connects a new server: pass name and url, and, only when the endpoint needs a key, a token together with authorization=bearer. " +
                        "update changes one existing server; only pass the fields the user asked about. " +
                        "delete removes a server together with its stored key, and refresh reconnects one server to read its current tool list. " +
                        "set_tool turns a single tool of a server on or off, while enable_all or tools chooses which tools a new or updated server offers. " +
                        "server accepts an id, a displayed name or the exact url. " +
                        "Keys are stored as secrets and are never echoed back, so after a change report the server name rather than the key. " +
                        "Only change what the user actually asked for, and state the result in your reply. " +
                        "Every change applies from the next reply, not to the answer you are writing now.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list")
                                                .put("add")
                                                .put("update")
                                                .put("delete")
                                                .put("refresh")
                                                .put("set_tool"),
                                        )
                                        .put("description", "Required. What to do with a server or one of its tools."),
                                )
                                .put(
                                    "server",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Server id, displayed name or exact url. Required by every action except list and add."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "add and update only: the name shown for this server."),
                                )
                                .put(
                                    "url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "add and update only: the MCP endpoint, for example https://example.com/mcp."),
                                )
                                .put(
                                    "token",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "add and update only: secret key for that endpoint. Omit it to keep the stored one, pass an empty string to clear it."),
                                )
                                .put(
                                    "authorization",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("none").put("bearer"))
                                        .put("description", "add and update only: bearer sends the token as an Authorization header. Default none, or bearer when a non-empty token is passed."),
                                )
                                .put(
                                    "protocol",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("auto").put("latest").put("legacy"))
                                        .put("description", "add and update only: which MCP revision to speak. Default auto, which negotiates."),
                                )
                                .put(
                                    "is_enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "add and update only: false keeps the server stored but stops offering its tools."),
                                )
                                .put(
                                    "enable_all",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "add and update only: true offers every tool the server reports."),
                                )
                                .put(
                                    "tools",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("maxItems", 60)
                                        .put("items", JSONObject().put("type", "string").put("maxLength", 200))
                                        .put("description", "add and update only: exact tool names to offer. A name the server does not report fails the call."),
                                )
                                .put(
                                    "tool",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "set_tool only: the tool name or title to turn on or off."),
                                )
                                .put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "set_tool only: true offers the tool, false hides it. Default true."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
    }
}
