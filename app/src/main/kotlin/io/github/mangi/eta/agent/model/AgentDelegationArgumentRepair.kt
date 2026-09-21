package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Bounded request repair; never invent task text, execute a tool or replay successful calls. */
internal class AgentDelegationArgumentRepair {
    var attempts: Int = 0
        private set
    var disabled: Boolean = false
        private set

    private var lastRepairRound: Int? = null

    fun reject(validationError: String, round: Int): AgentModelClient.ToolResult {
        // One bad batch is one repair opportunity, not one opportunity per malformed call.
        if (lastRepairRound != round && !disabled) {
            lastRepairRound = round
            if (attempts >= MAX_REPAIRS) disabled = true else attempts++
        }
        return AgentModelClient.ToolResult(JSONObject()
            .put("ok", false)
            .put("code", if (disabled) "DELEGATION_ARGUMENT_REPAIR_EXHAUSTED" else REPAIR_CODE)
            .put("executed", false)
            .put("task_created", false)
            .put("repair_attempt", attempts)
            .put("max_repairs", MAX_REPAIRS)
            .put("message", if (disabled) {
                "委派参数连续无效，本轮已停用新委派。没有创建子任务；已有子任务仍可查询。请自行完成任务或明确说明无法完成。"
            } else {
                "委派参数预检未通过：$validationError。未创建子任务。请根据现有用户要求重新生成完整 delegate_task 调用，" +
                    "必须包含非空 task；需要背景时提供 context，并遵循 role/project/workspace_id 的约束。" +
                    "只修复这个未执行的调用，不要重放已经成功执行的其它工具或委派。不能确定任务内容时不要猜测。"
            }).toString(), sensitive = true)
    }

    fun availableTools(catalog: JSONArray): JSONArray {
        if (!disabled) return catalog
        return JSONArray().also { filtered ->
            for (index in 0 until catalog.length()) {
                val entry = catalog.get(index)
                if ((entry as? JSONObject)?.optJSONObject("function")?.optString("name") != TOOL) filtered.put(entry)
            }
        }
    }

    companion object {
        const val TOOL = "delegate_task"
        const val REPAIR_CODE = "DELEGATION_ARGUMENT_REPAIR"
        const val MAX_REPAIRS = 2
    }
}
