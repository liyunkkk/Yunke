package io.github.mangi.eta.agent.delegation

import org.json.JSONObject

/**
 * 子代理工具失败时给主模型的下一步说明。只说明怎样改这次调用或改用哪个工具，
 * 不放宽任何校验，也不建议重放付费媒体或不确定的副作用。
 */
internal object SubAgentErrorHints {
    private val hints = mapOf(
        "INVALID_TASK_ARGUMENTS" to "参数不合法，没有创建或改动任务。按提示修正这一次调用，不要重放已经成功的调用。",
        "TASK_NOT_FOUND" to "本会话没有这个 task_id。先用不带 task_id 的 get_task_result 列出任务，再用列表里的 ID。",
        "UNKNOWN_REPLACED_TASK" to "replace_task_id 不属于本会话。先用不带 task_id 的 get_task_result 列出任务，确认要替换的 ID。",
        "TASK_FINISHED" to "这个任务所在的任务组已经归档，只能用 get_task_result 读取结果，不能再控制、继续或在这里新建任务。",
        "TASK_RESULT_PENDING" to "任务组正在收尾，结果还没归档。稍后用 get_task_result 再读一次。",
        "RUN_CLOSED" to "本轮子任务组已关闭，不能再委派或控制子任务。需要的话由主代理自己完成。",
        "TASK_GROUP_PAUSED" to "子任务组已暂停（主代理暂停、停止或正在收尾）。本轮不要重试 delegate_task；可以用 get_task_result 读旧任务，剩下的工作由主代理自己完成。",
        "TASK_NOT_RUNNING_TEXT" to "supervise_task 只能用于 status=running 的文本任务。queued 先等它开始；awaiting_decision 用 continue_task 或 cancel_task；已结束的任务只能用 get_task_result 读结果。",
        "TASK_NOT_AWAITING_DECISION" to "continue_task 只能用于 status=awaiting_decision 的任务。先用 get_task_result 看 status。",
        "TASK_NOT_ACCEPTING_GUIDANCE" to "任务正在收尾，或上一条指导还没被处理，暂时不接受新的指导。",
        "TASK_DISPATCH_REJECTED" to "调度队列拒绝了这个任务，没有创建。稍后最多再试一次。",
        "AGENT_NOT_CONFIGURED" to "agent_id 不在当前可用的 worker 里。只用 delegate_task 描述中 Available workers 列出的 agent_id。",
        "WORKER_ID_MISMATCH" to "agent_id 和 worker 指向不同的 worker。只填其中一个。",
        "WORKER_ROLE_MISMATCH" to "所选 worker 的配置角色不支持这个 role。换一个该角色的 worker，或去掉 agent_id/worker 让系统按 role 自动选择。",
        "ROLE_NOT_CONFIGURED" to "没有配置这个 role 的 worker。换一个已配置的 role，或由主代理自己完成。",
        "IMAGE_GENERATION_UNAVAILABLE" to "当前没有可用的生图 worker，不要重试。",
        "VIDEO_GENERATION_UNAVAILABLE" to "当前没有可用的生视频 worker，不要重试。",
        "REPLACEMENT_NOT_ALLOWED" to "只能替换 status=failed 且 can_replace=true 的任务。健康、暂停、已取消或已完成的任务不能替换；暂停的用 continue_task。",
        "REPLACE_PENDING_STOP" to "旧任务还没停止，已请求停止。用 get_task_result 确认 execution_stopped=true 后再发起替换。",
        "REPLACEMENT_REQUIRES_NEW_WORKER" to "替换任务必须交给另一个 worker（换 agent_id），不能交回原来的 worker。",
        "REPLACEMENT_PROVIDER_UNAVAILABLE" to "旧任务的供应商不可用，替换任务要选另一个供应商的 worker。",
        "REPLACEMENT_ROLE_MISMATCH" to "替换任务的 role 必须和旧任务一样；省略 role 即沿用旧任务的角色。",
        "REPLACEMENT_ALREADY_DISPATCHED" to "这个旧任务已经有替换任务，不要再派一次；直接查询返回的 task_id。",
        "REPLACEMENT_ALREADY_CLAIMED" to "这个旧任务的替换正在派发或已派发，不要再派一次；用 get_task_result 列表核对。",
        "REPLACEMENT_DISPATCH_UNKNOWN" to "替换任务是否已经派发不确定，不要再派一次；先用不带 task_id 的 get_task_result 核对。",
        "REPLACEMENT_EVIDENCE_UNAVAILABLE" to "读不到旧任务的状态，无法替换。先用 get_task_result 读旧任务。",
        "HANDOFF_NOT_READ" to "替换前要先对旧任务调用 get_task_result，读到它最新的状态后再替换。",
        "WORKSPACE_HANDOFF_REQUIRES_MANUAL_REVIEW" to "实现任务或带工作区的任务不能用 replace_task_id 替换。先用 manage_agent_workspace inspect 查看工作区，再决定 discard 后重新委派，或交给 review。",
        "MEDIA_DELIVERY_UNCERTAIN" to "付费媒体任务不能替换或重放。",
        "WORKSPACE_IN_USE" to "这个项目或工作区上还有活动的子任务。等它结束，或先 cancel_task，再操作工作区。",
        "WORKSPACE_UNAVAILABLE" to "工作区功能当前不可用（需要已就绪的 Linux 环境、git 和 python3）。不要委派 implementation，也不要调用 manage_agent_workspace。",
        "WORKSPACE_PREPARE_FAILED" to "隔离工作树创建失败。确认 project 是 /workspace 下的 git 仓库根目录。",
        "PROJECT_HAS_UNCOMMITTED_CHANGES" to "主项目有未提交的改动，不能创建隔离工作树。先提交或 stash 主项目的改动，再委派 implementation。",
        "UNCOMMITTED_CHANGES" to "主项目或工作树有未提交的改动，不能合并。先提交或清理改动再 merge。",
        "PROJECT_MUST_BE_GIT_ROOT" to "project 必须是 git 仓库的根目录。",
        "PROJECT_MUST_BE_WORKSPACE_CHILD" to "project 必须是 /workspace/<名称> 这样的目录。",
        "WORKSPACE_DISK_SPACE_LOW" to "可用空间不足 512MB，无法创建工作树。",
        "REVIEW_REQUIRED" to "合并前要先有一个 review 子任务（带 project 和 workspace_id）完成审查。",
        "PROJECT_MOVED_REVIEW_AGAIN" to "审查之后主项目又有新提交，要重新 review 再 merge。",
        "WORKSPACE_NOT_OWNED" to "这个 workspace_id 不属于当前会话。用 action=list 查看本会话的工作区。",
        "WORKSPACE_NOT_FOUND" to "找不到这个工作区。用 action=list 查看本会话的工作区。",
        "WORKSPACE_NOT_READY" to "工作区还没进入可审查状态（实现任务未完成或工作树有未提交改动）。",
    )

    fun message(code: String): String? = hints[code]

    /** 已带 message 的结果不覆盖；只给缺说明的失败补一句下一步。 */
    fun annotate(json: JSONObject): JSONObject {
        if (json.optBoolean("ok", true)) return json
        if (json.optString("message").isNotBlank()) return json
        message(json.optString("code"))?.let { json.put("message", it) }
        return json
    }
}
