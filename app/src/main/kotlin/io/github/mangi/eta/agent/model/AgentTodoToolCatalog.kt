package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话 Todo 清单工具 schema。
 *
 * 宿主管理、用户只读：模型用完整快照替换清单，用户在聊天界面看到进度。
 * 移植自 Operit-Ry 的 ChatTodo（工具名保持一致：todowrite）。
 */
internal object AgentTodoToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "todowrite",
                description = "用完整的新快照替换当前会话的 Todo 清单；用户会在聊天界面看到该清单。" +
                    "任务包含至少三个独立步骤、多个用户要求或其他非简单执行时使用；" +
                    "简单一步操作或仅回答信息时不要使用。" +
                    "收尾前必须把清单更新到终态：没做或不需要做的项标 cancelled，不要留下 in_progress；" +
                    "只有确实在等用户或外部条件时才保留 pending。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "todos",
                            JSONObject()
                                .put("type", "string")
                                .put(
                                    "description",
                                    "JSON 编码的完整有序对象数组的字符串（不是数组本身）：" +
                                        "[{content, status, priority}]；" +
                                        "status 为 pending | in_progress | completed | cancelled；" +
                                        "priority 为 high | medium | low；最多一项 in_progress"
                                )
                        )
                    )
                    .put("required", JSONArray().put("todos"))
            )
        )
    }
}
